package com.mar2sdk.core.common.net

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal data class PlatformLoginRequestInfo(
	val appID: Int,
	val appVersion: String,
	val sdkVersion: String,
	val deviceID: String,
	val accountID: String,
	val deviceTime: Long,
	val zoneOffset: String,
	val language: String,
)

internal object PlatformLoginProtocol {
	fun createRequest(url: String, info: PlatformLoginRequestInfo, clientKey: String): Request {
		require(info.appID > 0) { "appID must be positive" }
		require(clientKey.isNotBlank()) { "clientKey is required" }
		require(info.appVersion.isNotBlank() && info.sdkVersion.isNotBlank()) { "App and SDK versions are required" }
		require(info.deviceID.isNotBlank() && info.accountID.isNotBlank()) { "Device and guest identifiers are required" }
		val params = linkedMapOf(
			"appID" to info.appID.toString(),
			"timestamp" to (info.deviceTime / 1000).toString(),
			"accountType" to "1",
			"accountID" to info.accountID,
			"deviceID" to info.deviceID,
			"channelID" to "0",
		)
		params["sign"] = sign(params, clientKey)
		val body = FormBody.Builder().apply {
			params.forEach { (key, value) -> add(key, value) }
		}.build()
		return Request.Builder()
			.url(url)
			.header("appID", info.appID.toString())
			.header("appVersion", info.appVersion)
			.header("sdkVersion", info.sdkVersion)
			.header("deviceID", info.deviceID)
			.header("platformId", "2")
			.header("deviceTime", info.deviceTime.toString())
			.header("zoneOffset", info.zoneOffset)
			.header("Accept-Language", info.language)
			.post(body)
			.build()
	}

	fun sign(params: Map<String, String>, clientKey: String): String {
		val raw = params.filter { (key, value) -> key != "sign" && value.isNotEmpty() }
			.toSortedMap()
			.entries.joinToString("") { (key, value) -> "$key=$value&" } + "secretKey=$clientKey"
		return md5(raw).uppercase(Locale.ROOT)
	}

	fun md5(value: String): String = MessageDigest.getInstance("MD5")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

	fun parseResponse(body: String, loginTime: Long): PlatformLoginUser {
		try {
			val root = JSONObject(body)
			val code = root.requiredLong("code")
			if (code !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw IOException("Invalid response code")
			if (code != 0L) {
				throw ServerApiException(code.toInt(), root.optionalString("msg") ?: "Platform login failed ($code)")
			}
			return readUser(root.getJSONObject("data"), loginTime)
		} catch (exception: JSONException) {
			throw IOException("Invalid platform login response", exception)
		}
	}

	fun encodeUser(user: PlatformLoginUser): String = JSONObject().apply {
		put("uid", user.uid.toString())
		put("name", user.name)
		put("loginName", user.loginName)
		put("displayType", user.displayType)
		put("token", user.token)
		put("expiredTime", user.expiredTime)
		put("registerTime", user.registerTime)
		put("countryCode", user.countryCode ?: JSONObject.NULL)
		put("accountType", user.accountType)
		put("newAccount", user.newAccount)
		put("loginTime", user.loginTime)
	}.toString()

	fun decodeUser(body: String): PlatformLoginUser {
		val root = JSONObject(body)
		return readUser(root, root.requiredLong("loginTime"))
	}

	private fun readUser(data: JSONObject, loginTime: Long): PlatformLoginUser {
		val uid = data.requiredLong("uid")
		val token = data.optionalString("token")?.takeIf { it.isNotBlank() }
			?: throw IOException("Missing login token")
		val expiredTime = data.requiredLong("expiredTime")
		if (uid <= 0 || expiredTime <= 0) throw IOException("Invalid login credentials")
		return PlatformLoginUser(
			uid = uid,
			name = data.getString("name"),
			loginName = data.optionalString("loginName").orEmpty(),
			displayType = data.optInt("displayType", 1),
			token = token,
			expiredTime = expiredTime,
			registerTime = data.requiredLong("registerTime"),
			countryCode = data.optionalString("countryCode"),
			accountType = data.getInt("accountType"),
			newAccount = data.optInt("newAccount", 0),
			loginTime = loginTime,
		)
	}

	// JSONObject.getLong 对字符串可能经过 Double 转换，不能用于读取 64 位 uid。
	private fun JSONObject.requiredLong(key: String): Long = opt(key)?.toString()?.toLongOrNull()
		?: throw IOException("Missing or invalid $key")

	private fun JSONObject.optionalString(key: String): String? = opt(key) as? String
}

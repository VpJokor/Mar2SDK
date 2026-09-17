package com.mar2sdk.core.common.net

import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal object PlatformLoginProtocol {
	fun createRequest(url: String, info: ServerRequestInfo, accountID: String, clientKey: String): Request {
		require(accountID.isNotBlank()) { "Guest identifier is required" }
		return ServerApiProtocol.createFormRequest(
			url = url,
			info = info,
			clientKey = clientKey,
			businessParams = mapOf(
				"accountType" to "1",
				"accountID" to accountID,
				"deviceID" to info.deviceID,
				"channelID" to "0",
			),
		)
	}

	fun parseResponse(body: String, loginTime: Long): PlatformLoginUser {
		try {
			val root = ServerApiProtocol.parseResponse(body)
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

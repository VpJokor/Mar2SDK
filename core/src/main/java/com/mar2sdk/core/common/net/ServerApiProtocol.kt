package com.mar2sdk.core.common.net

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal data class ServerRequestInfo(
	val appID: Int,
	val appVersion: String,
	val sdkVersion: String,
	val deviceID: String,
	val deviceTime: Long,
	val zoneOffset: String,
	val language: String,
)

internal object ServerApiProtocol {
	fun createFormRequest(
		url: String,
		info: ServerRequestInfo,
		clientKey: String,
		businessParams: Map<String, String>,
	): Request {
		require(info.appID > 0) { "appID must be positive" }
		require(clientKey.isNotBlank()) { "clientKey is required" }
		require(info.appVersion.isNotBlank() && info.sdkVersion.isNotBlank()) { "App and SDK versions are required" }
		require(info.deviceID.isNotBlank()) { "Device identifier is required" }
		require(businessParams["deviceID"] == null || businessParams["deviceID"] == info.deviceID) {
			"Header and form deviceID must match"
		}
		val params = businessParams.filterValues { it.isNotEmpty() }.toMutableMap().apply {
			put("appID", info.appID.toString())
			put("timestamp", (info.deviceTime / 1000).toString())
		}
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

	/** 业务成功看 code；初始化上报可以不返回 data 或 msg。 */
	fun parseResponse(body: String): JSONObject {
		try {
			val root = JSONObject(body)
			val code = root.opt("code")?.toString()?.toIntOrNull()
				?: throw IOException("Missing or invalid response code")
			if (code != 0) {
				throw ServerApiException(code, root.opt("msg") as? String ?: "Server request failed ($code)")
			}
			return root
		} catch (exception: JSONException) {
			throw IOException("Invalid server response", exception)
		}
	}
}

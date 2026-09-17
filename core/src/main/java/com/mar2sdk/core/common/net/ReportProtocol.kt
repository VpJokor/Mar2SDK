package com.mar2sdk.core.common.net

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal object ReportProtocol {
	const val PATH = "/report/data/report"
	private val currencyPattern = Regex("[A-Za-z]{3}")

	fun createRequest(
		url: String,
		info: ServerRequestInfo,
		clientKey: String,
		uid: Long,
		packageName: String,
		events: JSONArray,
	): Request {
		require(uid > 0) { "A logged-in user is required" }
		require(packageName.isNotBlank()) { "Package name is required" }
		require(events.length() > 0) { "At least one report event is required" }
		// 补充账号时不修改调用方事件；事件 ID 和采集时的完整属性保持原值。
		val batch = JSONArray(events.toString())
		for (index in 0 until batch.length()) {
			val event = batch.optJSONObject(index)
				?: throw IllegalArgumentException("Report event must be an object")
			val eventName = event.opt("#event_name") as? String
			require(!eventName.isNullOrBlank()) { "Report event name is required" }
			require(event.opt("#type") == "track") { "Report event type must be track" }
			for (field in listOf("#time", "#distinct_id", "#uuid")) {
				require((event.opt(field) as? String)?.isNotBlank() == true) { "$field is required" }
			}
			val eventID = event.opt("#event_id")
			require(
				(eventID is String && eventID.isNotBlank()) ||
					(eventID is Number && eventID.toDouble().isFinite())
			) { "#event_id is required" }
			val accountID = event.opt("#account_id")
			if (accountID == null || accountID == JSONObject.NULL) {
				event.put("#account_id", uid)
			} else {
				require(accountID.toString() == uid.toString()) { "Event account must match the logged-in user" }
			}
			val properties = event.optJSONObject("properties")
				?: throw IllegalArgumentException("Event properties are required")
			require(properties.opt("#bundle_id") == packageName) { "Event package must match the current application" }
			if (eventName == "ad_revenue") {
				val value = properties.opt("value")
				require(value is Number && value.toDouble().isFinite()) { "Revenue value must be a finite number" }
				val currency = properties.opt("currency") as? String
				require(currency != null && currencyPattern.matches(currency)) { "Currency must be a three-letter code" }
			}
		}
		// 最终 data 只序列化一次，由公共协议对同一字符串签名并交给 FormBody 编码。
		val data = batch.toString()
		return ServerApiProtocol.createFormRequest(url, info, clientKey, mapOf("data" to data))
	}

	fun parseResponse(body: String) {
		ServerApiProtocol.parseResponse(body)
	}
}

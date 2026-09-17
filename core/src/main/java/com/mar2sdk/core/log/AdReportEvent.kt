package com.mar2sdk.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

internal object AdReportEvent {

	private val currencyPattern = Regex("[A-Za-z]{3}")

	fun create(
		eventName: String,
		params: Map<String, Any>,
		uid: Long,
		distinctId: String,
		packageName: String,
		presetProperties: JSONObject,
		superProperties: JSONObject,
		commonProperties: JSONObject,
		timeMillis: Long = System.currentTimeMillis(),
		timeZone: TimeZone = TimeZone.getDefault(),
		uuid: String = UUID.randomUUID().toString(),
		eventId: Long = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L),
	): JSONObject {
		require(eventName.isNotBlank()) { "Report event name is required" }
		require(uid > 0) { "A logged-in user is required" }
		require(distinctId.isNotBlank()) { "ThinkingData distinct ID is required" }
		require(packageName.isNotBlank()) { "Package name is required" }
		require(uuid.isNotBlank() && eventId > 0) { "Event identifiers are required" }

		val properties = linkedMapOf<String, Any>()
		for (source in listOf(presetProperties, superProperties, commonProperties)) {
			for (key in source.keys()) {
				if (source === presetProperties || !key.startsWith("#")) {
					properties[key] = source.get(key)
				}
			}
		}
		for ((key, value) in params) {
			if (!key.startsWith("#")) properties[key] = value
		}
		properties["#bundle_id"] = packageName
		properties["#zone_offset"] = timeZone.getOffset(timeMillis) / 3_600_000.0
		if (eventName == "ad_revenue") {
			val value = properties["value"]
			require(value is Number && value.toDouble().isFinite()) { "Revenue value must be a finite number" }
			val currency = properties["currency"] as? String
			require(currency != null && currencyPattern.matches(currency)) { "Currency must be a three-letter code" }
		}

		val eventTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
			.apply { this.timeZone = timeZone }.format(Date(timeMillis))
		return JSONObject()
			.put("#event_name", eventName)
			.put("#type", "track")
			.put("#time", eventTime)
			.put("#account_id", uid)
			.put("#distinct_id", distinctId)
			.put("#uuid", uuid)
			.put("#event_id", eventId)
			// 复制嵌套的 JSON、列表和映射，避免调用方后续修改影响事件快照。
			.put("properties", JSONObject(JSONObject(properties).toString()))
	}
}

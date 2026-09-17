package com.mar2sdk.core.log

import com.mar2sdk.core.common.net.ReportProtocol
import com.mar2sdk.core.common.net.ServerRequestInfo
import java.math.BigDecimal
import java.util.TimeZone
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdReportEventTest {

	@Test
	fun keepsLoginAndEventIdentifiersExactThroughSerialization() {
		val source = create(uuid = "event-uuid", eventId = 1542198364433551364L)
		val event = JSONObject(source.toString())

		assertEquals("ad_impression", event.getString("#event_name"))
		assertEquals("track", event.getString("#type"))
		assertEquals(1542198364433551363L, event.getLong("#account_id"))
		assertEquals("thinking-distinct-id", event.getString("#distinct_id"))
		assertEquals("event-uuid", event.getString("#uuid"))
		assertEquals(1542198364433551364L, event.getLong("#event_id"))
	}

	@Test
	fun constructedAdEventsPassTheReportProtocolWithoutChangingCallbackValues() {
		val paidParams = mapOf(
			"ad_platform" to "ADMOB", "ad_source" to "AdMob", "ad_unit_name" to "unit-001",
			"areakey" to "openPageAdv", "ad_format" to "OPEN", "ad_preload" to false,
			"value" to 0.000153, "currency" to "USD",
		)
		val names = listOf("ad_revenue", "ad_impression", "ad_click")
		val firstEventId = 1542198364433551364L
		val events = JSONArray().apply {
			for ((index, name) in names.withIndex()) {
				val params = if (name == "ad_click") mapOf("format" to "OPEN", "duration_time" to 14356L) else paidParams
				put(create(name, params, uuid = "uuid-$name", eventId = firstEventId + index))
			}
		}
		val original = events.toString()

		val request = ReportProtocol.createRequest(
			url = "https://example.test/report/data/report",
			info = ServerRequestInfo(10019, "1.0.0", "1.0.0", "device-id", 1787137000123L, "0", "en-US"),
			clientKey = "test-client-key",
			uid = 1542198364433551363L,
			packageName = "com.example.app",
			events = events,
		)
		val body = request.body as FormBody
		val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
		val submitted = JSONArray(fields.getValue("data"))

		assertEquals(3, submitted.length())
		for ((index, name) in names.withIndex()) {
			val event = submitted.getJSONObject(index)
			assertEquals(name, event.getString("#event_name"))
			assertEquals(1542198364433551363L, event.getLong("#account_id"))
			assertEquals(firstEventId + index, event.getLong("#event_id"))
			assertEquals("uuid-$name", event.getString("#uuid"))
			assertEquals("com.example.app", event.getJSONObject("properties").getString("#bundle_id"))
		}
		for (index in 0..1) {
			val properties = submitted.getJSONObject(index).getJSONObject("properties")
			assertEquals(0.000153, properties.getDouble("value"), 0.0)
			assertEquals("USD", properties.getString("currency"))
			assertEquals("OPEN", properties.getString("ad_format"))
		}
		val clickProperties = submitted.getJSONObject(2).getJSONObject("properties")
		assertEquals("OPEN", clickProperties.getString("format"))
		assertEquals(14356L, clickProperties.getLong("duration_time"))
		assertFalse(clickProperties.has("value"))
		assertEquals(original, events.toString())
		assertEquals("10019", fields.getValue("appID"))
		assertTrue(fields.getValue("sign").matches(Regex("[0-9A-F]{32}")))
	}

	@Test
	fun mergesBusinessPropertiesWithoutAllowingSystemPropertyOverrides() {
		val preset = JSONObject().put("#os", "Android").put("#bundle_id", "old.package")
			.put("#zone_offset", -12).put("campaign", "preset")
		val superProperties = JSONObject().put("campaign", "super").put("super", true)
			.put("#os", "super-os")
		val common = JSONObject().put("campaign", "common").put("network", "Google")
			.put("#os", "common-os")
		val params = mapOf(
			"campaign" to "event", "ad_unit_name" to "ad-unit-001", "ad_preload" to false,
			"ad_platform" to "ADMOB", "ad_source" to "AdMob", "areakey" to "openPageAdv",
			"duration_time" to 14356L, "format" to "OPEN", "ad_format" to "NATIVE",
			"#os" to "fake-os", "#bundle_id" to "fake.package", "#zone_offset" to 20,
			"#account_id" to "fake-account",
		)

		val properties = create(params = params, preset = preset, superProperties = superProperties, common = common)
			.getJSONObject("properties")

		assertEquals("event", properties.getString("campaign"))
		assertTrue(properties.getBoolean("super"))
		assertEquals("Google", properties.getString("network"))
		assertEquals("Android", properties.getString("#os"))
		assertEquals("com.example.app", properties.getString("#bundle_id"))
		assertEquals(0.0, properties.getDouble("#zone_offset"), 0.0)
		assertFalse(properties.has("#account_id"))
		for ((key, value) in params.filterKeys { !it.startsWith("#") }) {
			assertEquals(value.toString(), properties.get(key).toString())
		}
		assertEquals("old.package", preset.getString("#bundle_id"))
		assertEquals("super-os", superProperties.getString("#os"))
		assertEquals("common-os", common.getString("#os"))
	}

	@Test
	fun snapshotsNestedJsonMapsAndListsWithoutChangingTheSources() {
		val presetNested = JSONObject().put("enabled", true)
		val superNested = JSONArray().put("original")
		val commonNested = JSONObject().put("campaign", "original")
		val nestedMap = mutableMapOf<String, Any>("value" to "original")
		val nestedList = mutableListOf<Any>("original", JSONObject.NULL)
		val params = mutableMapOf<String, Any>("map" to nestedMap, "list" to nestedList)
		val preset = JSONObject().put("preset", presetNested)
		val superProperties = JSONObject().put("super", superNested)
		val common = JSONObject().put("common", commonNested)
		val originals = listOf(preset, superProperties, common).map { it.toString() }

		val event = create(params = params, preset = preset, superProperties = superProperties, common = common)
		assertEquals(originals, listOf(preset, superProperties, common).map { it.toString() })
		assertEquals(2, params.size)
		presetNested.put("enabled", false)
		superNested.put(0, "changed")
		commonNested.put("campaign", "changed")
		nestedMap["value"] = "changed"
		nestedList[0] = "changed"
		params.clear()

		val properties = event.getJSONObject("properties")
		assertTrue(properties.getJSONObject("preset").getBoolean("enabled"))
		assertEquals("original", properties.getJSONArray("super").getString(0))
		assertEquals("original", properties.getJSONObject("common").getString("campaign"))
		assertEquals("original", properties.getJSONObject("map").getString("value"))
		assertEquals("original", properties.getJSONArray("list").getString(0))
		assertTrue(properties.getJSONArray("list").isNull(1))
	}

	@Test
	fun usesFractionalTimeZoneAndPreservesMilliseconds() {
		val event = create(timeMillis = 281L, timeZone = TimeZone.getTimeZone("Asia/Kolkata"))
		assertEquals("1970-01-01 05:30:00.281", event.getString("#time"))
		assertEquals(5.5, event.getJSONObject("properties").getDouble("#zone_offset"), 0.0)
	}

	@Test
	fun usesDaylightSavingOffsetAtTheEventTime() {
		val zone = TimeZone.getTimeZone("America/New_York")
		val winter = create(timeMillis = 1767225600000L, timeZone = zone)
		val summer = create(timeMillis = 1782864000000L, timeZone = zone)
		assertEquals("2025-12-31 19:00:00.000", winter.getString("#time"))
		assertEquals(-5.0, winter.getJSONObject("properties").getDouble("#zone_offset"), 0.0)
		assertEquals("2026-06-30 20:00:00.000", summer.getString("#time"))
		assertEquals(-4.0, summer.getJSONObject("properties").getDouble("#zone_offset"), 0.0)
	}

	@Test
	fun preservesRevenueAmountAndAcceptsOnlyFiniteNumbersAndThreeAsciiLetters() {
		for (value in listOf(0, 0.000153, BigDecimal("0.000123"))) {
			val properties = create("ad_revenue", mapOf("value" to value, "currency" to "usd"))
				.getJSONObject("properties")
			assertEquals(value.toDouble(), properties.getDouble("value"), 0.0)
			assertEquals("usd", properties.getString("currency"))
		}
		for (value in listOf("0.1", true, JSONObject.NULL, Double.NaN, Double.POSITIVE_INFINITY, BigDecimal("1E400"))) {
			assertThrows(IllegalArgumentException::class.java) {
				create("ad_revenue", mapOf("value" to value, "currency" to "USD"))
			}
		}
		for (currency in listOf("", "US", "USDD", "123", "美元元", JSONObject.NULL, 123)) {
			assertThrows(IllegalArgumentException::class.java) {
				create("ad_revenue", mapOf("value" to 0.1, "currency" to currency))
			}
		}
		for (params in listOf(emptyMap(), mapOf("value" to 0.1), mapOf("currency" to "USD"))) {
			assertThrows(IllegalArgumentException::class.java) { create("ad_revenue", params) }
		}
	}

	@Test
	fun rejectsMissingLoginIdentityAndInvalidEventIdentifiers() {
		for (uid in listOf(0L, -1L)) {
			assertThrows(IllegalArgumentException::class.java) { create(uid = uid) }
		}
		for (blank in listOf("", " ")) {
			assertThrows(IllegalArgumentException::class.java) { create(distinctId = blank) }
			assertThrows(IllegalArgumentException::class.java) { create(packageName = blank) }
			assertThrows(IllegalArgumentException::class.java) { create(uuid = blank) }
		}
		for (eventId in listOf(0L, -1L)) {
			assertThrows(IllegalArgumentException::class.java) { create(eventId = eventId) }
		}
		assertThrows(IllegalArgumentException::class.java) { create("app_start") }
	}

	@Test
	fun generatesSeparateIdentifiersForEventsCapturedInTheSameMillisecond() {
		val (first, second) = List(2) {
			AdReportEvent.create(
				"ad_click", emptyMap(), 1542198364433551363L, "thinking-distinct-id", "com.example.app",
				JSONObject(), JSONObject(), JSONObject(), timeMillis = 0L,
			)
		}
		assertEquals(first.getString("#time"), second.getString("#time"))
		assertNotEquals(first.getString("#uuid"), second.getString("#uuid"))
		assertNotEquals(first.getLong("#event_id"), second.getLong("#event_id"))
		assertTrue(first.getLong("#event_id") > 0)
		assertTrue(second.getLong("#event_id") > 0)
	}

	private fun create(
		eventName: String = "ad_impression",
		params: Map<String, Any> = emptyMap(),
		uid: Long = 1542198364433551363L,
		distinctId: String = "thinking-distinct-id",
		packageName: String = "com.example.app",
		preset: JSONObject = JSONObject(),
		superProperties: JSONObject = JSONObject(),
		common: JSONObject = JSONObject(),
		timeMillis: Long = 0L,
		timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
		uuid: String = "event-uuid",
		eventId: Long = 1542198364433551364L,
	): JSONObject = AdReportEvent.create(
		eventName, params, uid, distinctId, packageName, preset, superProperties, common,
		timeMillis, timeZone, uuid, eventId,
	)
}

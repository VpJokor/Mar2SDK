package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.net.ReportProtocol
import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.ServerRequestInfo
import java.io.IOException
import java.math.BigDecimal
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportProtocolTest {

	@Test
	fun preservesMixedEventsAndLargeIdsWithoutMutatingCallerData() {
		val revenue = event("ad_revenue").put("#event_id", 1542198364433551364L)
		val impression = event("ad_impression")
			.put("#account_id", JSONObject.NULL)
			.put("#event_id", "impression-event-001")
		val click = event("ad_click").put("#account_id", UID.toString())
		val source = JSONArray().put(revenue).put(impression).put(click)
		val original = source.toString()

		val request = createRequest(source)
		val sent = JSONArray(fields(request).getValue("data"))

		assertEquals(3, sent.length())
		assertEquals(UID, sent.getJSONObject(0).getLong("#account_id"))
		assertEquals(UID, sent.getJSONObject(1).getLong("#account_id"))
		assertEquals(UID.toString(), sent.getJSONObject(2).getString("#account_id"))
		assertEquals(1542198364433551364L, sent.getJSONObject(0).getLong("#event_id"))
		for (index in 0 until source.length()) {
			val expected = JSONObject(source.getJSONObject(index).toString())
			if (expected.isNull("#account_id")) expected.put("#account_id", UID)
			assertEquals("Event $index must retain all original fields", expected.toString(), sent.getJSONObject(index).toString())
		}
		assertEquals(original, source.toString())
		assertFalse(revenue.has("#account_id"))
		assertTrue(impression.isNull("#account_id"))
		assertEquals(fields(request).getValue("data"), fields(createRequest(source)).getValue("data"))
	}

	@Test
	fun signsExactlyTheSubmittedJsonAndEncodesFormValuesOnce() {
		val source = JSONArray().put(event("ad_revenue").apply {
			getJSONObject("properties").put("campaign_name", "中文+广告&variant=1")
		})
		val request = createRequest(source)
		val body = request.body as FormBody
		val fields = fields(request)

		assertEquals("POST", request.method)
		assertEquals("https://example.test/report/data/report", request.url.toString())
		assertEquals("application/x-www-form-urlencoded", body.contentType().toString())
		assertEquals(setOf("appID", "timestamp", "data", "sign"), fields.keys)
		assertEquals("10019", fields["appID"])
		assertEquals("1787137000", fields["timestamp"])
		val raw = "appID=10019&data=${fields.getValue("data")}&timestamp=1787137000&secretKey=test-secret"
		val expectedSign = MessageDigest.getInstance("MD5")
			.digest(raw.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02X".format(it.toInt() and 0xff) }
		assertEquals(expectedSign, fields["sign"])
		val encoded = Buffer().also { body.writeTo(it) }.readUtf8()
		assertTrue(encoded.contains("%E4%B8%AD%E6%96%87%2B%E5%B9%BF%E5%91%8A%26variant%3D1"))
		val decoded = encoded.split("&").associate { parameter ->
			val parts = parameter.split("=", limit = 2)
			URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
		}
		assertEquals(fields, decoded)
		assertEquals(
			"中文+广告&variant=1",
			JSONArray(decoded.getValue("data")).getJSONObject(0)
				.getJSONObject("properties").getString("campaign_name")
		)
		val headers = mapOf(
			"appID" to "10019",
			"appVersion" to "1.0.0",
			"sdkVersion" to "1.5.0",
			"deviceID" to "device-001",
			"platformId" to "2",
			"deviceTime" to "1787137000123",
			"zoneOffset" to "5.5",
			"Accept-Language" to "zh-CN"
		)
		for ((name, value) in headers) assertEquals(value, request.header(name))
	}

	@Test
	fun rejectsEmptyBatchesAndNonObjectEvents() {
		for (batch in listOf(JSONArray(), JSONArray().put("event"), JSONArray().put(JSONObject.NULL))) {
			assertThrows(IllegalArgumentException::class.java) { createRequest(batch) }
		}
	}

	@Test
	fun requiresLoginAndRejectsMismatchedAccountWithoutLosingLongPrecision() {
		for (uid in listOf(0L, -1L)) {
			assertThrows(IllegalArgumentException::class.java) { createRequest(uid = uid) }
		}
		for (account in listOf(UID + 1, (UID + 1).toString(), "", true, JSONObject())) {
			val source = event().put("#account_id", account)
			assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
		}
		val source = event().put("#account_id", UID)
		val sent = JSONArray(fields(createRequest(JSONArray().put(source))).getValue("data"))
		assertEquals(UID, sent.getJSONObject(0).getLong("#account_id"))
	}

	@Test
	fun requiresOriginalIdentifiersAndValidTrackEventFields() {
		for (name in listOf("#time", "#distinct_id", "#uuid", "#event_id", "#type", "#event_name", "properties")) {
			val source = event().apply { remove(name) }
			assertThrows("Missing $name", IllegalArgumentException::class.java) {
				createRequest(JSONArray().put(source))
			}
		}
		for (name in listOf("#time", "#distinct_id", "#uuid")) {
			for (value in listOf("", " ", JSONObject.NULL, 42)) {
				assertThrows("Invalid $name", IllegalArgumentException::class.java) {
					createRequest(JSONArray().put(event().put(name, value)))
				}
			}
		}
		for (value in listOf("", " ", true, JSONObject(), JSONArray(), JSONObject.NULL)) {
			assertThrows(IllegalArgumentException::class.java) {
				createRequest(JSONArray().put(event().put("#event_id", value)))
			}
		}
		assertThrows(IllegalArgumentException::class.java) {
			createRequest(JSONArray().put(event().put("#type", "user_set")))
		}
		assertThrows(IllegalArgumentException::class.java) {
			createRequest(JSONArray().put(event("purchase")))
		}
	}

	@Test
	fun requiresPropertiesForTheCurrentApplication() {
		for (value in listOf(JSONObject.NULL, JSONArray(), "properties")) {
			assertThrows(IllegalArgumentException::class.java) {
				createRequest(JSONArray().put(event().put("properties", value)))
			}
		}
		for (bundleId in listOf("", "com.other.app", JSONObject.NULL, 123)) {
			val source = event().apply { getJSONObject("properties").put("#bundle_id", bundleId) }
			assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
		}
		val source = event().apply { getJSONObject("properties").remove("#bundle_id") }
		assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
	}

	@Test
	fun requiresFiniteNumericRevenueAndThreeLetterCurrency() {
		for (value in listOf("0.1", JSONObject.NULL, true, JSONObject(), BigDecimal("1E+400"))) {
			val source = event("ad_revenue").apply { getJSONObject("properties").put("value", value) }
			assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
		}
		for (currency in listOf("", "US", "USDD", "123", "美元元", JSONObject.NULL, 123)) {
			val source = event("ad_revenue").apply { getJSONObject("properties").put("currency", currency) }
			assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
		}
		for (name in listOf("value", "currency")) {
			val source = event("ad_revenue").apply { getJSONObject("properties").remove(name) }
			assertThrows(IllegalArgumentException::class.java) { createRequest(JSONArray().put(source)) }
		}
		createRequest(JSONArray().put(event("ad_revenue").apply {
			getJSONObject("properties").put("value", 0).put("currency", "usd")
		}))
	}

	@Test
	fun acceptsSuccessfulResponseWithoutDataOrMessage() {
		ReportProtocol.parseResponse("{\"code\":0}")
	}

	@Test
	fun surfacesPositiveAndNegativeBusinessErrors() {
		for (code in listOf(-1, 1003)) {
			val error = assertThrows(ServerApiException::class.java) {
				ReportProtocol.parseResponse("{\"code\":$code,\"msg\":\"report rejected\"}")
			}
			assertEquals(code, error.code)
			assertEquals("report rejected", error.message)
		}
	}

	@Test
	fun rejectsMalformedResponseAndMissingOrInvalidCode() {
		for (body in listOf("not-json", "{}", "{\"code\":null}", "{\"code\":\"invalid\"}")) {
			assertThrows(IOException::class.java) { ReportProtocol.parseResponse(body) }
		}
	}

	@Test
	fun rejectsHttpErrorEvenWhenResponseCodeIndicatesSuccess() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 503, "{\"code\":0}"))
		}
		val error = runCatching { NetUtil.requestReport(request, Call.Factory { call }) }.exceptionOrNull()
		assertTrue(error is IOException)
		assertTrue(error?.message.orEmpty().contains("503"))
	}

	@Test
	fun propagatesBusinessErrorFromSuccessfulHttpResponse() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":-1,\"msg\":\"sign not matched\"}"))
		}
		val error = runCatching { NetUtil.requestReport(request, Call.Factory { call }) }.exceptionOrNull()
		assertTrue(error is ServerApiException)
		assertEquals(-1, (error as ServerApiException).code)
		assertEquals("sign not matched", error.message)
	}

	@Test
	fun acceptsSuccessfulHttpAndBusinessResponse() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":0}"))
		}
		NetUtil.requestReport(request, Call.Factory { call })
		assertTrue(call.isExecuted())
	}

	@Test
	fun coroutineCancellationCancelsPendingCallAndToleratesItsFailureCallback() = runTest {
		val request = createRequest()
		val call = FakeCall(request)
		val reporting = launch(start = CoroutineStart.UNDISPATCHED) {
			NetUtil.requestReport(request, Call.Factory { call })
		}
		assertTrue(call.isExecuted())

		reporting.cancelAndJoin()
		call.callback.onFailure(call, IOException("Canceled"))

		assertTrue(reporting.isCancelled)
		assertTrue(call.isCanceled())
	}

	private fun event(name: String = "ad_impression"): JSONObject = JSONObject()
		.put("#time", "2026-08-26 03:45:32.281")
		.put("#distinct_id", "distinct-001")
		.put("#uuid", "uuid-$name")
		.put("#event_id", 1542198364433551364L)
		.put("#type", "track")
		.put("#event_name", name)
		.put("extra", JSONObject().put("items", JSONArray().put("custom").put(JSONObject.NULL)))
		.put("properties", JSONObject()
			.put("#bundle_id", PACKAGE_NAME)
			.put("#os", "Android")
			.put("#screen_height", 2340)
			.put("ad_preload", false)
			.put("ad_source", "AdMob")
			.put("duration_time", 14356)
			.apply {
				if (name == "ad_revenue") put("value", 0.000153).put("currency", "USD")
			})

	private fun createRequest(events: JSONArray = JSONArray().put(event()), uid: Long = UID): Request =
		ReportProtocol.createRequest(
			url = "https://example.test/report/data/report",
			info = ServerRequestInfo(
				appID = 10019,
				appVersion = "1.0.0",
				sdkVersion = "1.5.0",
				deviceID = "device-001",
				deviceTime = 1787137000123L,
				zoneOffset = "5.5",
				language = "zh-CN"
			),
			clientKey = "test-secret",
			uid = uid,
			packageName = PACKAGE_NAME,
			events = events
		)

	private fun fields(request: Request): Map<String, String> = (request.body as FormBody).let { body ->
		(0 until body.size).associate { body.name(it) to body.value(it) }
	}

	private fun response(request: Request, status: Int, body: String): Response = Response.Builder()
		.request(request)
		.protocol(Protocol.HTTP_1_1)
		.code(status)
		.message("Test response")
		.body(body.toResponseBody())
		.build()

	private class FakeCall(
		private val request: Request,
		private val respond: ((Call, Callback) -> Unit)? = null,
	) : Call {
		lateinit var callback: Callback
		private var cancelled = false
		private var executed = false

		override fun request(): Request = request
		override fun execute(): Response = error("Expected an asynchronous call")
		override fun enqueue(responseCallback: Callback) {
			executed = true
			callback = responseCallback
			respond?.invoke(this, responseCallback)
		}
		override fun cancel() { cancelled = true }
		override fun isExecuted(): Boolean = executed
		override fun isCanceled(): Boolean = cancelled
		override fun timeout(): Timeout = Timeout.NONE
		override fun clone(): Call = FakeCall(request, respond)
	}

	private companion object {
		const val UID = 1542198364433551363L
		const val PACKAGE_NAME = "com.example.app"
	}
}

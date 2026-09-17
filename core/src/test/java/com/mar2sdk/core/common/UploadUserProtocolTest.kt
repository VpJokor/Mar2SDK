package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.ServerRequestInfo
import com.mar2sdk.core.common.net.UploadUserProtocol
import java.io.IOException
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadUserProtocolTest {

	@Test
	fun mapsAttributionAndSignsRawValuesWithPreciseUidAndCommonHeaders() {
		val attribution = JSONObject()
			.put("network", "Google Ads")
			.put("campaign_id", "123456789")
			.put("campaign_name", "系列 中文+A&B=1")
			.put("subcampaign_id", "987654321")
			.put("subcampaign_name", "广告组+测试")
			.put("creative_id", "456789123")
			.put("creative_name", "video/01?x=1")
			.put("token", "ignored-token")
			.put("accountID", "ignored-account")
			.put("channelID", 0)
			.put("deviceID", "ignored-device")
		val request = createRequest(attribution)

		assertEquals("POST", request.method)
		assertEquals("https://example.test/server/user/uploadUser", request.url.toString())
		val expectedHeaders = mapOf(
			"appID" to "10019",
			"appVersion" to "1.0.0",
			"sdkVersion" to "1.5.0",
			"deviceID" to "device-001",
			"platformId" to "2",
			"deviceTime" to "1787137000123",
			"zoneOffset" to "5.5",
			"Accept-Language" to "zh-CN"
		)
		for ((name, value) in expectedHeaders) {
			assertEquals(name, value, request.header(name))
		}
		val body = request.body as FormBody
		assertEquals("application/x-www-form-urlencoded", body.contentType().toString())
		assertEquals(
			mapOf(
				"appID" to "10019",
				"timestamp" to "1787137000",
				"uid" to "1375560669270130159",
				"network" to "Google Ads",
				"campaignID" to "123456789",
				"campaignName" to "系列 中文+A&B=1",
				"adGroupID" to "987654321",
				"adGroupName" to "广告组+测试",
				"creativeID" to "456789123",
				"creativeName" to "video/01?x=1",
				// Independently calculated over raw values, excluding all headers.
				"sign" to "740F00BC5A73C08A5CF4EF2F3D1937BF"
			),
			fields(body)
		)
		val encodedBody = Buffer().also { body.writeTo(it) }.readUtf8()
		assertTrue(encodedBody.contains("campaignName=%E7%B3%BB%E5%88%97%20%E4%B8%AD%E6%96%87%2BA%26B%3D1"))
	}

	@Test
	fun usesActualOrganicNetworkAndOmitsMissingOptionalFields() {
		val body = createRequest(JSONObject().put("network", "Organic Search")).body as FormBody
		val fields = fields(body)

		assertEquals(setOf("appID", "timestamp", "uid", "network", "sign"), fields.keys)
		assertEquals("Organic Search", fields["network"])
	}

	@Test
	fun omitsJsonNullAndBlankFieldsWhilePreservingActualValues() {
		val attribution = JSONObject()
			.put("network", "Organic")
			.put("campaign_id", JSONObject.NULL)
			.put("campaign_name", "")
			.put("subcampaign_id", " \t\n")
			.put("subcampaign_name", JSONObject.NULL)
			.put("creative_id", 123456789L)
			.put("creative_name", " video 01 ")
		val fields = fields(createRequest(attribution).body as FormBody)

		assertEquals(
			setOf("appID", "timestamp", "uid", "network", "creativeID", "creativeName", "sign"),
			fields.keys
		)
		assertEquals("123456789", fields["creativeID"])
		assertEquals(" video 01 ", fields["creativeName"])
	}

	@Test
	fun rejectsMissingNullOrBlankNetworkAndNonPositiveUid() {
		for (attribution in listOf(
			JSONObject(),
			JSONObject().put("network", JSONObject.NULL),
			JSONObject().put("network", ""),
			JSONObject().put("network", " \t\n"),
		)) {
			assertThrows(IllegalArgumentException::class.java) { createRequest(attribution) }
		}
		for (uid in listOf(0L, -1L)) {
			assertThrows(IllegalArgumentException::class.java) { createRequest(uid = uid) }
		}
	}

	@Test
	fun acceptsSuccessfulResponseWithoutDataOrMessage() {
		UploadUserProtocol.parseResponse("{\"code\":0}")
	}

	@Test
	fun surfacesPositiveAndNegativeBusinessErrors() {
		for (code in listOf(-1, 1003)) {
			val error = assertThrows(ServerApiException::class.java) {
				UploadUserProtocol.parseResponse("{\"code\":$code,\"msg\":\"attribution rejected\"}")
			}

			assertEquals(code, error.code)
			assertEquals("attribution rejected", error.message)
		}
	}

	@Test
	fun rejectsMalformedResponseAndMissingOrInvalidCode() {
		for (body in listOf("not-json", "{}", "{\"code\":null}", "{\"code\":\"invalid\"}")) {
			assertThrows(IOException::class.java) { UploadUserProtocol.parseResponse(body) }
		}
	}

	@Test
	fun rejectsHttpErrorEvenWhenResponseCodeIndicatesSuccess() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 503, "{\"code\":0}"))
		}

		val error = runCatching {
			NetUtil.requestUploadUser(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is IOException)
		assertTrue(error?.message.orEmpty().contains("503"))
	}

	@Test
	fun propagatesBusinessErrorFromSuccessfulHttpResponse() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":1003,\"msg\":\"attribution rejected\"}"))
		}

		val error = runCatching {
			NetUtil.requestUploadUser(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is ServerApiException)
		assertEquals(1003, (error as ServerApiException).code)
	}

	@Test
	fun coroutineCancellationCancelsPendingCallAndToleratesItsFailureCallback() = runTest {
		val request = createRequest()
		val call = FakeCall(request)
		val upload = launch(start = CoroutineStart.UNDISPATCHED) {
			NetUtil.requestUploadUser(request, Call.Factory { call })
		}
		assertTrue(call.isExecuted())

		upload.cancelAndJoin()
		call.callback.onFailure(call, IOException("Canceled"))

		assertTrue(upload.isCancelled)
		assertTrue(call.isCanceled())
	}

	private fun createRequest(
		attribution: JSONObject = JSONObject().put("network", "Organic"),
		uid: Long = 1375560669270130159L,
	): Request = UploadUserProtocol.createRequest(
		url = "https://example.test/server/user/uploadUser",
		info = ServerRequestInfo(
			appID = 10019,
			appVersion = "1.0.0",
			sdkVersion = "1.5.0",
			deviceID = "device-001",
			deviceTime = 1787137000123L,
			zoneOffset = "5.5",
			language = "zh-CN"
		),
		uid = uid,
		attribution = attribution,
		clientKey = "test-secret"
	)

	private fun fields(body: FormBody): Map<String, String> =
		(0 until body.size).associate { body.name(it) to body.value(it) }

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
}

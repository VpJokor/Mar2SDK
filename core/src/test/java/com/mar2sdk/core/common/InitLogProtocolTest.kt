package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.InitLogProtocol
import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.ServerRequestInfo
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InitLogProtocolTest {

	@Test
	fun signsRawDeviceInformationAndUsesConsistentIdentityAndTimeUnits() {
		val request = createRequest(deviceType = "Pixel+中文&variant=1", deviceDpi = "420")

		assertEquals("POST", request.method)
		assertEquals("https://example.test/server/user/initLog", request.url.toString())
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
		val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
		assertEquals(
			mapOf(
				"appID" to "10019",
				"timestamp" to "1787137000",
				"deviceID" to "device-001",
				"channelID" to "0",
				"deviceOS" to "1",
				"deviceType" to "Pixel+中文&variant=1",
				"deviceDpi" to "420",
				// Independently calculated from the raw values, including channelID=0.
				"sign" to "B749F91E7AC6561BF0AF7B956BF793B3"
			),
			fields
		)
		val encodedBody = Buffer().also { body.writeTo(it) }.readUtf8()
		assertTrue(encodedBody.contains("deviceType=Pixel%2B%E4%B8%AD%E6%96%87%26variant%3D1"))
	}

	@Test
	fun omitsUnavailableDeviceDetailsAndLoginFields() {
		val body = createRequest(deviceType = "", deviceDpi = "").body as FormBody
		val fieldNames = (0 until body.size).map { body.name(it) }.toSet()

		assertEquals(
			setOf("appID", "timestamp", "deviceID", "channelID", "deviceOS", "sign"),
			fieldNames
		)
	}

	@Test
	fun acceptsSuccessfulResponseWithoutDataOrMessage() {
		InitLogProtocol.parseResponse("{\"code\":0}")
	}

	@Test
	fun surfacesPositiveAndNegativeBusinessErrors() {
		for (code in listOf(-1, 1003)) {
			val error = assertThrows(ServerApiException::class.java) {
				InitLogProtocol.parseResponse("{\"code\":$code,\"msg\":\"initialization rejected\"}")
			}

			assertEquals(code, error.code)
			assertEquals("initialization rejected", error.message)
		}
	}

	@Test
	fun rejectsMalformedResponseAndMissingOrInvalidCode() {
		for (body in listOf("not-json", "{}", "{\"code\":null}", "{\"code\":\"invalid\"}")) {
			assertThrows(IOException::class.java) {
				InitLogProtocol.parseResponse(body)
			}
		}
	}

	@Test
	fun rejectsHttpErrorEvenWhenResponseCodeIndicatesSuccess() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 503, "{\"code\":0}"))
		}

		val error = runCatching {
			NetUtil.requestInitLog(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is IOException)
		assertTrue(error?.message.orEmpty().contains("503"))
	}

	@Test
	fun propagatesBusinessErrorFromSuccessfulHttpResponse() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":1003,\"msg\":\"initialization rejected\"}"))
		}

		val error = runCatching {
			NetUtil.requestInitLog(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is ServerApiException)
		assertEquals(1003, (error as ServerApiException).code)
	}

	@Test
	fun coroutineCancellationCancelsPendingCallAndToleratesItsFailureCallback() = runTest {
		val request = createRequest()
		val call = FakeCall(request)
		val initialization = launch(start = CoroutineStart.UNDISPATCHED) {
			NetUtil.requestInitLog(request, Call.Factory { call })
		}
		assertTrue(call.isExecuted())

		initialization.cancelAndJoin()
		call.callback.onFailure(call, IOException("Canceled"))

		assertTrue(initialization.isCancelled)
		assertTrue(call.isCanceled())
	}

	private fun createRequest(deviceType: String = "Pixel_8", deviceDpi: String = "420"): Request =
		InitLogProtocol.createRequest(
			url = "https://example.test/server/user/initLog",
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
			deviceType = deviceType,
			deviceDpi = deviceDpi
		)

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

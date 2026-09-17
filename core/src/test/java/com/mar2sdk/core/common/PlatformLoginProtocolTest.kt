package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.net.PlatformLoginProtocol
import com.mar2sdk.core.common.net.PlatformLoginUser
import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.ServerApiProtocol
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformLoginProtocolTest {

	@Test
	fun createsSignedFormWithConsistentIdentityAndCorrectTimeUnits() {
		val request = PlatformLoginProtocol.createRequest(
			"https://example.test/server/user/platformLogin",
			ServerRequestInfo(
				appID = 10019,
				appVersion = "1.0.0",
				sdkVersion = "1.5.0",
				deviceID = "device-001",
				deviceTime = 1787137000123L,
				zoneOffset = "5.5",
				language = "zh-CN"
			),
			"guest+中文&value=1",
			"test-secret"
		)

		assertEquals("POST", request.method)
		assertEquals("https://example.test/server/user/platformLogin", request.url.toString())
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
				"accountID" to "guest+中文&value=1",
				"accountType" to "1",
				"deviceID" to "device-001",
				"channelID" to "0",
				"sign" to "B57395EA91F83CFC615DA886D3E4649E"
			),
			fields
		)
		val encodedBody = Buffer().also { body.writeTo(it) }.readUtf8()
		assertTrue(encodedBody.contains("accountID=guest%2B%E4%B8%AD%E6%96%87%26value%3D1"))
	}

	@Test
	fun signsOriginalValuesInAsciiOrderIgnoringEmptyValuesAndExistingSignature() {
		val params = linkedMapOf(
			"timestamp" to "1787137000",
			"sign" to "obsolete-signature",
			"loginName" to "",
			"channelID" to "0",
			"accountType" to "1",
			"deviceID" to "device-001",
			"appID" to "10019",
			"extraData" to "",
			"accountID" to "guest+中文&value=1"
		)

		// Independently calculated MD5 of the UTF-8 raw values, before form encoding.
		assertEquals(
			"B57395EA91F83CFC615DA886D3E4649E",
			ServerApiProtocol.sign(params, "test-secret")
		)
	}

	@Test
	fun acceptsSuccessWithoutMessageAndPreservesLargeNumericOrStringIds() {
		for (asStrings in listOf(false, true)) {
			val response = successResponse(asStrings)
			val user = PlatformLoginProtocol.parseResponse(response.toString(), 1787137000456L)

			assertEquals(expectedUser(), user)
		}
	}

	@Test
	fun surfacesNegativeAndPositiveBusinessErrors() {
		for (code in listOf(-1, 1003)) {
			val response = JSONObject().put("code", code).put("msg", "login rejected")
			val error = assertThrows(ServerApiException::class.java) {
				PlatformLoginProtocol.parseResponse(response.toString(), 1787137000456L)
			}

			assertEquals(code, error.code)
			assertEquals("login rejected", error.message)
		}
	}

	@Test
	fun rejectsMalformedResponsesAndMissingCredentials() {
		val missingCode = successResponse().apply { remove("code") }
		val missingUid = successResponse().apply { getJSONObject("data").remove("uid") }
		val missingToken = successResponse().apply { getJSONObject("data").remove("token") }
		val invalidResponses = listOf(
			"not-json",
			"{}",
			"{\"code\":0}",
			missingCode.toString(),
			missingUid.toString(),
			missingToken.toString()
		)

		for (response in invalidResponses) {
			assertThrows(IOException::class.java) {
				PlatformLoginProtocol.parseResponse(response, 1787137000456L)
			}
		}
	}

	@Test
	fun persistsCredentialsAndLoginTimeIncludingNullableCountry() {
		for (country in listOf("HK", null)) {
			val user = expectedUser().copy(countryCode = country)

			assertEquals(user, PlatformLoginProtocol.decodeUser(PlatformLoginProtocol.encodeUser(user)))
		}
	}

	@Test
	fun rejectsHttpErrorEvenWhenBodyContainsLoginCredentials() = runTest {
		val request = Request.Builder().url("https://example.test/server/user/platformLogin").build()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 503, successResponse().toString()))
		}

		val error = runCatching {
			NetUtil.requestPlatformLogin(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is IOException)
		assertTrue(error?.message.orEmpty().contains("503"))
	}

	@Test
	fun propagatesBusinessErrorFromSuccessfulHttpResponse() = runTest {
		val request = Request.Builder().url("https://example.test/server/user/platformLogin").build()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":1003,\"msg\":\"login rejected\"}"))
		}

		val error = runCatching {
			NetUtil.requestPlatformLogin(request, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is ServerApiException)
		assertEquals(1003, (error as ServerApiException).code)
	}

	@Test
	fun coroutineCancellationCancelsPendingCallAndToleratesItsFailureCallback() = runTest {
		val request = Request.Builder().url("https://example.test/server/user/platformLogin").build()
		val call = FakeCall(request)
		val login = launch(start = CoroutineStart.UNDISPATCHED) {
			NetUtil.requestPlatformLogin(request, Call.Factory { call })
		}
		assertTrue(call.isExecuted())

		login.cancelAndJoin()
		call.callback.onFailure(call, IOException("Canceled"))

		assertTrue(login.isCancelled)
		assertTrue(call.isCanceled())
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

	private fun successResponse(asStrings: Boolean = true): JSONObject {
		val data = JSONObject()
			.put("uid", if (asStrings) "9007199254740993" else 9007199254740993L)
			.put("name", "guest-001")
			.put("loginName", "")
			.put("displayType", 1)
			.put("token", "server-issued-token")
			.put("expiredTime", if (asStrings) "2592000" else 2592000L)
			.put("registerTime", if (asStrings) "1787137000120" else 1787137000120L)
			.put("countryCode", "HK")
			.put("accountType", 1)
			.put("newAccount", 1)
		return JSONObject().put("code", 0).put("data", data)
	}

	private fun expectedUser() = PlatformLoginUser(
		uid = 9007199254740993L,
		name = "guest-001",
		loginName = "",
		displayType = 1,
		token = "server-issued-token",
		expiredTime = 2592000L,
		registerTime = 1787137000120L,
		countryCode = "HK",
		accountType = 1,
		newAccount = 1,
		loginTime = 1787137000456L
	)
}

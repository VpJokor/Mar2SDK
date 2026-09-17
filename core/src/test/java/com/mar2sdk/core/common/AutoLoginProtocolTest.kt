package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.AutoLoginProtocol
import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.net.PlatformLoginUser
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoginProtocolTest {

	@Test
	fun signsRawTokenAndSendsLoginCredentialsWithConsistentHeadersAndTimeUnits() {
		val request = createRequest()

		assertEquals("POST", request.method)
		assertEquals("https://example.test/server/user/autoLoginreflushtoken", request.url.toString())
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
				"uid" to "9007199254740993",
				"token" to "token+中文&value=1",
				"deviceID" to "device-001",
				"accountType" to "1",
				"username" to "guest-001",
				// Independently calculated MD5 of the raw values, before form encoding.
				"sign" to "0A36A67BF5856F40C7F899FF5D6362D1"
			),
			fields
		)
		val encodedBody = Buffer().also { body.writeTo(it) }.readUtf8()
		assertTrue(encodedBody.contains("token=token%2B%E4%B8%AD%E6%96%87%26value%3D1"))
	}

	@Test
	fun omitsEmptyUsernameFromBothFormAndSignature() {
		val body = createRequest(user = cachedUser().copy(name = "")).body as FormBody
		val fields = (0 until body.size).associate { body.name(it) to body.value(it) }

		assertEquals(setOf("appID", "timestamp", "uid", "token", "deviceID", "accountType", "sign"), fields.keys)
		assertEquals("9EC3396953260D3FDC25461CA6F78ED2", fields["sign"])
	}

	@Test
	fun rejectsMissingCredentialsAndInvalidConfigurationBeforeSending() {
		for (user in listOf(
			cachedUser().copy(uid = 0),
			cachedUser().copy(uid = -1),
			cachedUser().copy(token = ""),
			cachedUser().copy(token = " ")
		)) {
			assertThrows(IllegalArgumentException::class.java) { createRequest(user = user) }
		}
		assertThrows(IllegalArgumentException::class.java) { createRequest(info = requestInfo().copy(appID = 0)) }
		assertThrows(IllegalArgumentException::class.java) { createRequest(clientKey = "") }
	}

	@Test
	fun acceptsUnchangedTokenWhileRefreshingLoginTimeAndUserInformationWithoutLosingUidPrecision() {
		for (asStrings in listOf(false, true)) {
			val refreshed = AutoLoginProtocol.parseResponse(successResponse(asStrings).toString(), 1787137012345L, cachedUser().uid)

			assertEquals(
				cachedUser().copy(
					name = "renamed-guest",
					expiredTime = 1728000L,
					countryCode = "SG",
					newAccount = 0,
					loginTime = 1787137012345L
				),
				refreshed
			)
		}
	}

	@Test
	fun preservesExpiredTokenSignatureAndBanErrorCodes() {
		for (code in listOf(1002, -1, 1003)) {
			val error = assertThrows(ServerApiException::class.java) {
				AutoLoginProtocol.parseResponse("{\"code\":$code,\"msg\":\"token login rejected\"}", 1787137012345L, cachedUser().uid)
			}

			assertEquals(code, error.code)
			assertEquals("token login rejected", error.message)
		}
	}

	@Test
	fun rejectsDifferentAccountAndMissingResponseCredentials() {
		val differentUser = successResponse().apply { getJSONObject("data").put("uid", "9007199254740994") }
		val missingToken = successResponse().apply { getJSONObject("data").remove("token") }
		val missingUid = successResponse().apply { getJSONObject("data").remove("uid") }
		for (body in listOf(differentUser.toString(), missingToken.toString(), missingUid.toString(), "not-json", "{\"code\":0}")) {
			assertThrows(IOException::class.java) {
				AutoLoginProtocol.parseResponse(body, 1787137012345L, cachedUser().uid)
			}
		}
	}

	@Test
	fun rejectsHttpErrorEvenWhenResponseContainsValidCredentials() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 503, successResponse().toString()))
		}

		val error = runCatching {
			NetUtil.requestAutoLoginreflushtoken(request, cachedUser().uid, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is IOException)
		assertTrue(error?.message.orEmpty().contains("503"))
	}

	@Test
	fun propagatesTokenFailureFromSuccessfulHttpResponse() = runTest {
		val request = createRequest()
		val call = FakeCall(request) { pendingCall, callback ->
			callback.onResponse(pendingCall, response(request, 200, "{\"code\":1002,\"msg\":\"token expired\"}"))
		}

		val error = runCatching {
			NetUtil.requestAutoLoginreflushtoken(request, cachedUser().uid, Call.Factory { call })
		}.exceptionOrNull()

		assertTrue(error is ServerApiException)
		assertEquals(1002, (error as ServerApiException).code)
	}

	@Test
	fun coroutineCancellationCancelsPendingCallAndToleratesItsFailureCallback() = runTest {
		val request = createRequest()
		val call = FakeCall(request)
		val login = launch(start = CoroutineStart.UNDISPATCHED) {
			NetUtil.requestAutoLoginreflushtoken(request, cachedUser().uid, Call.Factory { call })
		}
		assertTrue(call.isExecuted())

		login.cancelAndJoin()
		call.callback.onFailure(call, IOException("Canceled"))

		assertTrue(login.isCancelled)
		assertTrue(call.isCanceled())
	}

	private fun createRequest(
		user: PlatformLoginUser = cachedUser(),
		info: ServerRequestInfo = requestInfo(),
		clientKey: String = "test-secret"
	): Request = AutoLoginProtocol.createRequest(
		url = "https://example.test/server/user/autoLoginreflushtoken",
		info = info,
		user = user,
		clientKey = clientKey
	)

	private fun requestInfo() = ServerRequestInfo(
		appID = 10019,
		appVersion = "1.0.0",
		sdkVersion = "1.5.0",
		deviceID = "device-001",
		deviceTime = 1787137000123L,
		zoneOffset = "5.5",
		language = "zh-CN"
	)

	private fun cachedUser() = PlatformLoginUser(
		uid = 9007199254740993L,
		name = "guest-001",
		loginName = "",
		displayType = 1,
		token = "token+中文&value=1",
		expiredTime = 2592000L,
		registerTime = 1787136000120L,
		countryCode = "HK",
		accountType = 1,
		newAccount = 1,
		loginTime = 1787137000000L
	)

	private fun successResponse(asStrings: Boolean = true): JSONObject = JSONObject()
		.put("code", 0)
		.put("data", JSONObject()
			.put("uid", if (asStrings) "9007199254740993" else 9007199254740993L)
			.put("name", "renamed-guest")
			.put("loginName", "")
			.put("displayType", 1)
			.put("token", cachedUser().token)
			.put("expiredTime", if (asStrings) "1728000" else 1728000L)
			.put("registerTime", if (asStrings) "1787136000120" else 1787136000120L)
			.put("countryCode", "SG")
			.put("accountType", 1)
			.put("newAccount", 0)
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

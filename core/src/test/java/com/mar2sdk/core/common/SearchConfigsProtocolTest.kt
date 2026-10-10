package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.SearchConfigsProtocol
import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.ServerRequestInfo
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SearchConfigsProtocolTest {

	@Test
	fun createsSignedFormWithAllSearchFields() {
		val request = SearchConfigsProtocol.createRequest(
			url = "https://example.test/server/user/searchConfigs",
			info = requestInfo(),
			uid = 123L,
			userType = "COMMON",
			firstOpenTime = "2026-09-09 14:56:12",
			network = "google",
			firstAdRevenue = "-1",
			campaignId = "123",
			campaignName = "launch",
			clientKey = "test-secret",
		)

		assertEquals("POST", request.method)
		assertEquals("https://example.test/server/user/searchConfigs", request.url.toString())
		val body = request.body as FormBody
		val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
		assertEquals(
			mapOf(
				"appID" to "10019",
				"timestamp" to "1787137000",
				"uid" to "123",
				"deviceID" to "device-001",
				"userType" to "COMMON",
				"firstOpenTime" to "2026-09-09 14:56:12",
				"network" to "google",
				"firstAdRevenue" to "-1",
				"campaignID" to "123",
				"campaignName" to "launch",
				"sign" to "8B40CEDE76F0C11520BA0D0B38CE05BE",
			),
			fields,
		)
	}

	@Test
	fun parsesReturnedUserTypeAndIndependentItems() {
		val response = SearchConfigsProtocol.parseResponse(
			"""
			{"code":0,"data":{"userType":"COMMON","items":[
			 {"type":"ad_config","userType":"COMMON","code":0,"id":"101","versionNo":3,"config":{"isOpen":true}},
			 {"type":"notification_config","userType":"COMMON","code":1060,"config":null}
			]}}
			""".trimIndent(),
		)

		assertEquals(0, response.code)
		assertEquals("COMMON", response.userType)
		assertEquals(2, response.items.size)
		assertEquals("ad_config", response.items[0].type)
		assertEquals(0, response.items[0].code)
		assertEquals(true, response.items[0].config?.getBoolean("isOpen"))
		assertEquals(1060, response.items[1].code)
		assertNull(response.items[1].config)
	}

	@Test
	fun treatsTopLevelNoConfigAsValidLocalFallback() {
		val response = SearchConfigsProtocol.parseResponse("{\"code\":1060,\"msg\":\"no config\"}")

		assertEquals(1060, response.code)
		assertNull(response.data)
	}

	@Test
	fun rejectsOtherBusinessErrorsAndMalformedSuccess() {
		val error = assertThrows(ServerApiException::class.java) {
			SearchConfigsProtocol.parseResponse("{\"code\":-1,\"msg\":\"signature invalid\"}")
		}
		assertEquals(-1, error.code)
		assertThrows(IOException::class.java) {
			SearchConfigsProtocol.parseResponse("{\"code\":0,\"data\":{}}")
		}
	}

	private fun requestInfo() = ServerRequestInfo(
		appID = 10019,
		appVersion = "1.0.0",
		sdkVersion = "1.5.0",
		deviceID = "device-001",
		deviceTime = 1787137000123L,
		zoneOffset = "5.5",
		language = "zh-CN",
	)
}

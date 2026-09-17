package com.mar2sdk.core.common.net

import okhttp3.Request

internal object InitLogProtocol {
	fun createRequest(
		url: String,
		info: ServerRequestInfo,
		clientKey: String,
		deviceType: String,
		deviceDpi: String,
	): Request = ServerApiProtocol.createFormRequest(
		url = url,
		info = info,
		clientKey = clientKey,
		businessParams = mapOf(
			"deviceID" to info.deviceID,
			"channelID" to "0",
			"deviceOS" to "1",
			"deviceType" to deviceType,
			"deviceDpi" to deviceDpi,
		),
	)

	fun parseResponse(body: String) {
		ServerApiProtocol.parseResponse(body)
	}
}

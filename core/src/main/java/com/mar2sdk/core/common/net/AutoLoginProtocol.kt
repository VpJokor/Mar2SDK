package com.mar2sdk.core.common.net

import okhttp3.Request
import java.io.IOException

internal object AutoLoginProtocol {
	fun createRequest(
		url: String,
		info: ServerRequestInfo,
		user: PlatformLoginUser,
		clientKey: String,
	): Request {
		require(user.uid > 0) { "uid must be positive" }
		require(user.token.isNotBlank()) { "Saved login token is required" }
		return ServerApiProtocol.createFormRequest(
			url = url,
			info = info,
			clientKey = clientKey,
			businessParams = mapOf(
				"uid" to user.uid.toString(),
				"token" to user.token,
				"deviceID" to info.deviceID,
				"accountType" to user.accountType.toString(),
				"username" to user.name,
			),
		)
	}

	fun parseResponse(body: String, loginTime: Long, expectedUid: Long): PlatformLoginUser {
		val user = PlatformLoginProtocol.parseResponse(body, loginTime)
		if (user.uid != expectedUid) throw IOException("Token login returned a different user")
		return user
	}
}

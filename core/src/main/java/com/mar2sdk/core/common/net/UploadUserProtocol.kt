package com.mar2sdk.core.common.net

import okhttp3.Request
import org.json.JSONObject

internal object UploadUserProtocol {
	fun createRequest(
		url: String,
		info: ServerRequestInfo,
		uid: Long,
		attribution: JSONObject,
		clientKey: String,
	): Request {
		require(uid > 0) { "uid must be positive" }
		val network = attribution.value("network")
		require(network != null) { "Attribution network is required" }
		val params = mutableMapOf("uid" to uid.toString(), "network" to network)
		val optionalFields = mapOf(
			"campaign_id" to "campaignID",
			"campaign_name" to "campaignName",
			"subcampaign_id" to "adGroupID",
			"subcampaign_name" to "adGroupName",
			"creative_id" to "creativeID",
			"creative_name" to "creativeName",
		)
		optionalFields.forEach { (source, target) ->
			attribution.value(source)?.let { params[target] = it }
		}
		return ServerApiProtocol.createFormRequest(url, info, clientKey, params)
	}

	fun parseResponse(body: String) {
		ServerApiProtocol.parseResponse(body)
	}

	private fun JSONObject.value(key: String): String? = opt(key)
		?.takeUnless { it == JSONObject.NULL }
		?.toString()
		?.takeIf { it.isNotBlank() }
}

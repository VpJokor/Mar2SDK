package com.mar2sdk.core.common.net

import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A server-side user classification response and the configurations selected for it. */
internal data class SearchConfigsResponse(
	val code: Int,
	val data: SearchConfigsData?,
) {
	val userType: String? get() = data?.userType
	val items: List<SearchConfigItem> get() = data?.items.orEmpty()
}

internal data class SearchConfigsData(
	val userType: String,
	val items: List<SearchConfigItem>,
)

internal data class SearchConfigItem(
	val type: String,
	val userType: String?,
	val code: Int,
	val id: String?,
	val versionNo: Int?,
	val config: JSONObject?,
)

internal object SearchConfigsProtocol {
	fun createRequest(
		url: String,
		info: ServerRequestInfo,
		uid: Long,
		userType: String,
		firstOpenTime: String,
		network: String,
		firstAdRevenue: String,
		campaignId: String,
		campaignName: String,
		clientKey: String,
	): Request {
		require(uid > 0) { "uid must be positive" }
		return ServerApiProtocol.createFormRequest(
			url = url,
			info = info,
			clientKey = clientKey,
			businessParams = mapOf(
				"uid" to uid.toString(),
				"deviceID" to info.deviceID,
				"userType" to userType,
				"firstOpenTime" to firstOpenTime,
				"network" to network,
				"firstAdRevenue" to firstAdRevenue,
				"campaignID" to campaignId,
				"campaignName" to campaignName,
			),
		)
	}

	/**
	 * Parses both a normal response (`code=0`) and the documented no-match response
	 * (`code=1060`). The latter is a valid response that carries no replacement config.
	 */
	fun parseResponse(body: String): SearchConfigsResponse {
		try {
			val root = JSONObject(body)
			val code = root.requiredInt("code")
			if (code != 0 && code != NO_SERVER_CONFIG) {
				throw ServerApiException(code, root.optString("msg").ifBlank { "Server request failed ($code)" })
			}
			if (code == NO_SERVER_CONFIG) return SearchConfigsResponse(code, null)

			val dataValue = root.opt("data")
			if (dataValue == null || dataValue == JSONObject.NULL) {
				throw IOException("Missing searchConfigs response data")
			}
			val data = dataValue as? JSONObject
				?: throw IOException("Invalid searchConfigs response data")
			val userType = data.requiredString("userType")
			val itemsValue = data.opt("items")
			if (itemsValue == null || itemsValue == JSONObject.NULL) {
				if (code == 0) throw IOException("Missing searchConfigs response items")
				return SearchConfigsResponse(code, SearchConfigsData(userType, emptyList()))
			}
			val items = itemsValue as? org.json.JSONArray
				?: throw IOException("Invalid searchConfigs response items")
			return SearchConfigsResponse(code, SearchConfigsData(userType, buildList {
				for (index in 0 until items.length()) {
					val item = items.opt(index) as? JSONObject
						?: throw IOException("Invalid searchConfigs item at index $index")
					add(parseItem(item, index))
				}
			}))
		} catch (exception: ServerApiException) {
			throw exception
		} catch (exception: JSONException) {
			throw IOException("Invalid searchConfigs response", exception)
		}
	}

	private fun parseItem(item: JSONObject, index: Int): SearchConfigItem {
		val type = item.requiredString("type")
		val code = item.requiredInt("code")
		val configValue = item.opt("config")
		val config = when {
			configValue == null || configValue == JSONObject.NULL -> null
			configValue is JSONObject -> configValue
			else -> throw IOException("Invalid searchConfigs config at index $index")
		}
		return SearchConfigItem(
			type = type,
			userType = item.optionalString("userType"),
			code = code,
			id = item.optionalValue("id"),
			versionNo = item.optionalInt("versionNo"),
			config = config,
		)
	}

	private fun JSONObject.requiredInt(key: String): Int = optionalInt(key)
		?: throw IOException("Missing or invalid $key")

	private fun JSONObject.optionalInt(key: String): Int? {
		val value = opt(key)
		if (value == null || value == JSONObject.NULL) return null
		return value.toString().toIntOrNull()
			?: throw IOException("Invalid $key")
	}

	private fun JSONObject.requiredString(key: String): String = optionalString(key)
		?.takeIf { it.isNotBlank() }
		?: throw IOException("Missing or invalid $key")

	private fun JSONObject.optionalString(key: String): String? {
		val value = opt(key)
		return if (value == null || value == JSONObject.NULL) null else value as? String
	}

	private fun JSONObject.optionalValue(key: String): String? {
		val value = opt(key)
		return if (value == null || value == JSONObject.NULL) null else value.toString()
	}

	private const val NO_SERVER_CONFIG = 1060
}

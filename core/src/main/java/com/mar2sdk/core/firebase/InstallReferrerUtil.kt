package com.mar2sdk.core.firebase

import android.net.Uri
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.UserInfo

object InstallReferrerUtil {
	private const val TAG = "InstallReferrerUtil"
	@Volatile
	private var initialized = false

	@Synchronized
	fun init() {
		if (initialized) return
		initialized = true

		var client: InstallReferrerClient? = null
		try {
			val referrerClient = InstallReferrerClient.newBuilder(Core.app.applicationContext).build()
			client = referrerClient
			referrerClient.startConnection(object : InstallReferrerStateListener {
				override fun onInstallReferrerSetupFinished(responseCode: Int) {
					try {
						if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
							Log.w(TAG, "Install Referrer connection failed: responseCode=$responseCode")
							return
						}
						applyAttribution(referrerClient.installReferrer.installReferrer)
					} catch (error: Exception) {
						Log.e(TAG, "Failed to read Install Referrer", error)
					} finally {
						closeConnection(referrerClient)
					}
				}

				override fun onInstallReferrerServiceDisconnected() {
					Log.w(TAG, "Install Referrer service disconnected")
				}
			})
		} catch (error: Exception) {
			initialized = false
			client?.let(::closeConnection)
			Log.e(TAG, "Failed to start Install Referrer", error)
		}
	}

	private fun closeConnection(client: InstallReferrerClient) {
		try {
			client.endConnection()
		} catch (error: Exception) {
			Log.w(TAG, "Failed to close Install Referrer connection", error)
		}
	}

	private fun applyAttribution(referrer: String?) {
		synchronized(UserInfo) {
			// Install Referrer is a first-install signal. Keep an attribution already
			// resolved by a previous run or another attribution callback.
			if (UserInfo.network.isNotBlank() &&
				!UserInfo.network.equals("unknow", ignoreCase = true) &&
				!UserInfo.network.equals("unknown", ignoreCase = true)
			) {
				return
			}
			val values = parseReferrer(referrer)
			val source = values["utm_source"] ?: values["source"]
			val campaignId = values["campaign_id"] ?: values["utm_campaign_id"]
			val campaignName = values["campaign_name"] ?: values["utm_campaign"]
			val network = when {
				!source.isNullOrBlank() -> source
				values.containsKey("gclid") -> "google"
				referrer.isNullOrBlank() -> "organic"
				else -> "google_play"
			}

			UserInfo.network = network
			UserInfo.campaignId = campaignId?.takeIf { it.isNotBlank() } ?: "unknow"
			UserInfo.campaignName = campaignName?.takeIf { it.isNotBlank() } ?: "unknow"
			UserInfo.saveUserInfo()
			RiskUtil.judgeUserType()

			val attributes = mutableMapOf<String, Any>()
			attributes.put("network", network)
			attributes.put("fromNature", network.equals("organic", ignoreCase = true))
			campaignId?.takeIf { it.isNotBlank() }?.let { attributes.put("campaign_id", it) }
			campaignName?.takeIf { it.isNotBlank() }?.let { attributes.put("campaign_name", it) }
			Core.setUserAttr(attributes)
			Log.i(TAG, "Install Referrer attribution applied: network=$network")
		}
	}

	private fun parseReferrer(referrer: String?): Map<String, String> {
		if (referrer.isNullOrBlank()) return emptyMap()
		val uri = Uri.parse("https://play.google.com/?$referrer")
		return uri.queryParameterNames.associateWith { key -> uri.getQueryParameter(key).orEmpty() }
	}

}

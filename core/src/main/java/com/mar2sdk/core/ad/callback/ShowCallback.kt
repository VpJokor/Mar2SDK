package com.mar2sdk.core.ad.callback

import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.ShowFailResult

interface ShowCallback {
	var areaKey: String
	var adFormat: AdFormat
	var adPlatform: AdPlatform
	fun showFailed(reason: ShowFailResult)
	fun showSuccess()
	fun onClicked()
	fun onAdClosed()
	fun onPaid()
}
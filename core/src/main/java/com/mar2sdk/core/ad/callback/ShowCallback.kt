package com.mar2sdk.core.ad.callback

import com.mar2sdk.core.ad.status.ShowFailResult

interface ShowCallback {
	var areaKey: String
	fun showFailed(reason: ShowFailResult)
	fun showSuccess()
	fun onClicked()
	fun onAdClosed()
	fun onPaid()
}
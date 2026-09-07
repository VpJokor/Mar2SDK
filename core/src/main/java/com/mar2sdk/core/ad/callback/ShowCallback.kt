package com.mar2sdk.core.ad.callback

import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.ShowFailResult

interface ShowCallback {
	val adContext: ScreenAdContext
	fun showFailed(reason: ShowFailResult)
	fun showSuccess()
	fun onClicked()
	fun onAdClosed()
	fun onPaid()
	fun onReward()
}

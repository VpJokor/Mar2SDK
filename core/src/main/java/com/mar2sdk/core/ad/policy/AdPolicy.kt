package com.mar2sdk.core.ad.policy

import com.mar2sdk.core.ad.AdConfig


object AdPolicy {
	fun canShowAd(adContext: ScreenAdContext) : Boolean {
		if (!AdConfig.isOpen) return false

		return true
	}
}
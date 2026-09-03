package com.mar2sdk.core.ad

import com.mar2sdk.core.ad.impl.admob.AdmobLoader
import com.mar2sdk.core.ad.status.AdPlatform

// 广告加载器
object AdLoader {

	suspend fun fillAd() {
		fillAd(AdConfig.defaultPlatform)
		AdConfig.activePlatforms.forEach { adPlatform ->
			fillAd(adPlatform)
		}
	}

	private suspend fun fillAd(platform: AdPlatform) {
		when(platform) {
			AdPlatform.ADMOB -> AdmobLoader.fillPool()
			// TODO: IMPLEMENT OTHER PLATFORM
			else -> {

			}
		}
	}
}
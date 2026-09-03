package com.mar2sdk.core.ad

import com.mar2sdk.core.ad.impl.admob.AdmobIniter
import com.mar2sdk.core.ad.impl.max.MaxIniter
import com.mar2sdk.core.ad.impl.topon.ToponIniter
import com.mar2sdk.core.ad.impl.tradplus.TradplusIniter
import com.mar2sdk.core.ad.impl.unity.UnityIniter
import com.mar2sdk.core.ad.status.AdPlatform

/**
 * 广告SDK初始化器
 */
object AdIniter {

	//初始化广告SDK
	fun init() {
		initDefault()
	}

	fun initDefault() {
		initAd(AdConfig.defaultPlatform)
	}

	fun initActives() {
		AdConfig.activePlatforms.forEach { adPlatform ->
			initAd(adPlatform)
		}
	}

	fun initAd(adPlatform: AdPlatform) {
		when(adPlatform) {
			AdPlatform.ADMOB -> AdmobIniter.init()
			AdPlatform.MAX -> MaxIniter.init()
			AdPlatform.TOPON -> ToponIniter.init()
			AdPlatform.TRADPLUS -> TradplusIniter.init()
			AdPlatform.UNITY -> UnityIniter.init()
		}
	}
}

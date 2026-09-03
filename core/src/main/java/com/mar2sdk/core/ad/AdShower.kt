package com.mar2sdk.core.ad

import android.app.Activity
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.admob.AdmobShower
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus

// 广告展示器
object AdShower {

	// 一直等待到 AdmobShower.showOpen 返回结果再返回
	suspend fun showOpen(activity: Activity, callback: ShowCallback, adPlatform: AdPlatform = AdConfig.defaultPlatform): AdShowStatus {
		callback.adPlatform = adPlatform
		return when(adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showOpen(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showOpen(activity, callback)
		}
	}

	suspend fun showInter(activity: Activity, callback: ShowCallback, adPlatform: AdPlatform = AdConfig.defaultPlatform): AdShowStatus {
		callback.adPlatform = adPlatform
		return when(adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showInter(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showInter(activity, callback)
		}
	}

	suspend fun showVideo(activity: Activity, callback: ShowCallback, adPlatform: AdPlatform = AdConfig.defaultPlatform): AdShowStatus {
		callback.adPlatform = adPlatform
		return when(adPlatform) {
			AdPlatform.ADMOB -> AdmobShower.showVideo(activity, callback)
			// TODO implement Max/UNITY/TRADPLUS/TOPON
			else -> AdmobShower.showVideo(activity, callback)
		}
	}

}

package com.mar2sdk.core.ad

import android.app.Activity
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.AdmobShower
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus

// 广告展示器
object AdShower {
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus {
		callback.adPlatform = AdPlatform.ADMOB
//		AdmobShower.showOpen(activity, callback)
	}

	fun showInter(activity: Activity, callback: ShowCallback) {
		callback.adPlatform = AdPlatform.ADMOB
//		AdmobShower.showInter(activity, callback)
	}

	fun showVideo(activity: Activity, callback: ShowCallback) {
		callback.adPlatform = AdPlatform.ADMOB
//		AdmobShower.showVideo(activity, callback)
	}

}
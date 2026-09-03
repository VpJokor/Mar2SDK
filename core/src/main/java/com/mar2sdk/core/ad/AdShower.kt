package com.mar2sdk.core.ad

import android.app.Activity
import com.inmobi.media.re
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.AdmobShower
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus

// 广告展示器
object AdShower {

	// 一直等待到 AdmobShower.showOpen 返回结果再返回
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus {
		callback.adPlatform = AdPlatform.ADMOB
		return AdmobShower.showOpen(activity, callback)
	}

	suspend fun showInter(activity: Activity, callback: ShowCallback): AdShowStatus {
		callback.adPlatform = AdPlatform.ADMOB
		return AdmobShower.showInter(activity, callback)
	}

	suspend fun showVideo(activity: Activity, callback: ShowCallback): AdShowStatus {
		callback.adPlatform = AdPlatform.ADMOB
		return AdmobShower.showVideo(activity, callback)
	}

}

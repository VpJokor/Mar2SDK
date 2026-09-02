package com.mar2sdk.core.ad

import android.app.Activity
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.AdmobShower

// 广告展示器
object AdShower {
	fun showOpen(activity: Activity, callback: ShowCallback) {
		AdmobShower.showOpen(activity, callback)
	}

	fun showInter(activity: Activity, callback: ShowCallback) {
		AdmobShower.showInter(activity, callback)
	}

	fun showVideo(activity: Activity, callback: ShowCallback) {
		AdmobShower.showVideo(activity, callback)
	}

}
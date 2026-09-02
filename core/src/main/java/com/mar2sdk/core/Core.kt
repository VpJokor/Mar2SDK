package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat

/**
 * 核心库入口
 */
object Core {

	lateinit var app: Application
	lateinit var appMod: AppMod

	// 初始化SDK
	fun init(app: Application, appMod: AppMod) {
		this@Core.app = app
		this@Core.appMod = appMod
		// 初始化广告SDK
		AdIniter.init(app)
	}

	// 展示开屏
	fun showOpen(activity: Activity, callback: ShowCallback) {
		callback.adFormat = AdFormat.OPEN
		AdShower.showOpen(activity, callback)
	}

	// 展示插屏
	fun showInter(activity: Activity, callback: ShowCallback) {
		AdShower.showInter(activity, callback)
	}

	// 展示视频
	fun showVideo(activity: Activity, callback: ShowCallback) {
		AdShower.showVideo(activity, callback)
	}

}
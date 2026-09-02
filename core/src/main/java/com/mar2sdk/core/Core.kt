package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.firebase.FirebaseUtil
import com.mar2sdk.core.firebase.SingularUtil
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.police.UserType

/**
 * 核心库入口
 */
object Core {

	lateinit var app: Application
	lateinit var appMod: AppMod
	// 用户分类
	var userType = UserType.NATURE

	// 初始化SDK
	fun init(app: Application, appMod: AppMod) {
		this@Core.app = app
		this@Core.appMod = appMod
		Config.initConfig()
		// 初始化广告SDK
		AdIniter.init()
		// 初始化Firebase
		FirebaseUtil.init()
		// 初始化Singular
		SingularUtil.init()
		// 初始化数数
		ThinkingUtil.init()
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
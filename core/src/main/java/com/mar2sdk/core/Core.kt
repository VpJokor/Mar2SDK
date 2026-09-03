package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.firebase.FirebaseUtil
import com.mar2sdk.core.firebase.SingularUtil
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.policy.RiskUtil
import com.mar2sdk.core.policy.UserType

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
		// 风控辅助初始化
		RiskUtil.init()
		// 设置用户属性
		setUserParams()
	}

	// 展示开屏
	suspend fun showOpen(activity: Activity, callback: ShowCallback): AdShowStatus {
		callback.adFormat = AdFormat.OPEN
		return AdShower.showOpen(activity, callback)
	}

	// 展示插屏
	suspend fun showInter(activity: Activity, callback: ShowCallback) {
		AdShower.showInter(activity, callback)
	}

	// 展示视频
	suspend fun showVideo(activity: Activity, callback: ShowCallback) {
		AdShower.showVideo(activity, callback)
	}

	private fun setUserParams() {
		ThinkingUtil.setUserOnceAttr("appMod", appMod.name)
		ThinkingUtil.setUserAttr("userType", userType.name)
	}

}

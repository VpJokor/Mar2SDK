package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import com.inmobi.media.re
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.firebase.FirebaseUtil
import com.mar2sdk.core.firebase.SingularUtil
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.notify.NotificationUtil
import com.mar2sdk.core.notify.app.AppNotificationUtil
import com.mar2sdk.core.policy.RiskUtil
import com.mar2sdk.core.policy.TestMod
import com.mar2sdk.core.policy.UserType
import com.mar2sdk.core.util.AppObs

/**
 * 核心库入口
 */
object Core {

	lateinit var app: Application
	lateinit var appMod: AppMod
	// 用户分类
	var userType = UserType.NATURE
	var testMod = TestMod.POLICY

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
		// 上报appMod
		ThinkingUtil.setUserOnceAttr("appMod", Core.appMod.name)
		// APP通知初始化
		AppNotificationUtil.init()
		// 开始监听手机状态
		AppObs.init()
	}

	suspend fun showAd(activity: Activity, callback: ShowCallback, adFormat: AdFormat): AdShowStatus {
		callback.adFormat = adFormat
		return when(adFormat) {
			AdFormat.OPEN -> AdShower.showOpen(activity, callback)
			AdFormat.INTER -> AdShower.showInter(activity, callback)
			AdFormat.VIDEO -> AdShower.showVideo(activity, callback)
		}
	}

	// 展示开屏
	suspend fun showOpen(activity: Activity, callback: ShowCallback) : AdShowStatus {
		return AdShower.showOpen(activity, callback)
	}

	// 展示插屏
	suspend fun showInter(activity: Activity, callback: ShowCallback) : AdShowStatus {
		callback.adFormat = AdFormat.INTER
		return AdShower.showInter(activity, callback)
	}

	// 展示视频
	suspend fun showVideo(activity: Activity, callback: ShowCallback) : AdShowStatus {
		callback.adFormat = AdFormat.VIDEO
		return AdShower.showVideo(activity, callback)
	}

	// 日志上报
	fun log(eventName: String, params: Map<String, Any>) {
		LogUtil.log(eventName, params)
	}

	// 设置一次性用户属性
	fun setUserOnceAttr(key: String, value: String) {
		ThinkingUtil.setUserOnceAttr(key, value)
	}

	// 设置可覆盖用户属性
	fun setUserAttr(key: String, value: Any) {
		ThinkingUtil.setUserAttr(key, value)
	}

	//启动常驻通知栏
	fun startFGS() {
		NotificationUtil.startFGS()
	}
}

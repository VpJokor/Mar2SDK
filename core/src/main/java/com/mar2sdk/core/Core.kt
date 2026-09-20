package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.UMPUtil
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.common.AppObs
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.TestMod
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.common.net.NetUtil
import com.mar2sdk.core.common.status.UserType
import com.mar2sdk.core.firebase.FirebaseUtil
import com.mar2sdk.core.firebase.InstallReferrerUtil
import com.mar2sdk.core.firebase.SingularUtil
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.notify.NotificationUtil
import com.mar2sdk.core.notify.app.AppNotificationUtil

enum class AppMod {
	DEBUG,
	TEST,
	PRE_RELEASE,
	RELEASE
}

/**
 * 核心库入口
 */
object Core {

	private const val TAG = "Core"
	// 由 core/build.gradle.kts 中的 version 在构建时生成。
	const val SDK_VERSION = BuildConfig.SDK_VERSION

	lateinit var app: Application
	lateinit var appMod: AppMod
	// 用户类型, 优先使用服务器返回的结果，如果服务器判断还没下发则使用客户端判断结果
	var userType = if (UserInfo.netUserType == UserType.UNKNOW) {
		UserInfo.localUserType
	} else {
		UserInfo.netUserType
	}

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
		// 初始化InstallRefer
		InstallReferrerUtil.init()
		// 初始化数数
		ThinkingUtil.init()
		// 自动上报当前通知权限。
		NotificationUtil.reportNotificationPermission()
		// 风控辅助初始化
		RiskUtil.init()
		// 上报appMod
		setUserOnceAttr("appMod", Core.appMod.name)
		// APP通知初始化
		AppNotificationUtil.init()
		// 开始监听手机状态
		AppObs.init()
		// 每次初始化均上报设备信息，无需等待用户登录。
		NetUtil.initLog()
		// 自动登录：有效缓存使用 Token，否则执行游客登录。
		if (CommonConfig.isAutoLogin) {
			NetUtil.login()
		}
	}

	suspend fun showAd(activity: Activity, callback: ShowCallback): AdShowStatus {
		return when(callback.adContext.adFormat) {
			AdFormat.OPEN -> AdShower.showOpen(activity, callback)
			AdFormat.INTER -> AdShower.showInter(activity, callback)
			AdFormat.VIDEO -> AdShower.showVideo(activity, callback)
			AdFormat.OPEN_INTER -> AdShower.showOpenInter(activity, callback)
			AdFormat.INTER_VIDEO -> AdShower.showInterVideo(activity, callback)
			AdFormat.VIDEO_INTER -> AdShower.showVideoInter(activity, callback)
		}
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

	/** Initializes Google UMP consent state. Returns true when a recent result was cached. */
	fun initConsent(activity: Activity, onComplete: (success: Boolean) -> Unit): Boolean {
		return UMPUtil.initUMP(activity, onComplete)
	}

	/** Shows the UMP consent form during the startup flow, then invokes [onComplete]. */
	fun showSplashConsent(activity: Activity, onComplete: () -> Unit) {
		UMPUtil.showSplashUMP(activity, onComplete)
	}

	/** Opens the UMP privacy options form. */
	fun showPrivacyOptions(activity: Activity) {
		UMPUtil.showUMP(activity)
	}

	/** Whether UMP requires a privacy options entry point. */
	val isPrivacyOptionsRequired: Boolean
		get() = UMPUtil.isPrivacyOptionsRequired

	/** Direct UMP-named alias for [initConsent]. */
	fun initUMP(activity: Activity, onComplete: (success: Boolean) -> Unit): Boolean {
		return initConsent(activity, onComplete)
	}

	/** Direct UMP-named alias for [showSplashConsent]. */
	fun showSplashUMP(activity: Activity, onComplete: () -> Unit) {
		showSplashConsent(activity, onComplete)
	}

	/** Direct UMP-named alias for [showPrivacyOptions]. */
	fun showUMP(activity: Activity) {
		showPrivacyOptions(activity)
	}

	// 批量设置可覆盖用户属性，一次提交整组属性。
	fun setUserAttr(attributes: Map<String, Any>) {
		ThinkingUtil.setUserAttr(attributes)
	}

	// 设置 ThinkingData 公共事件属性，自动附加到后续事件。
	fun setEventAttr(key: String, value: Any) {
		ThinkingUtil.setEventAttr(key, value)
	}

	//启动常驻通知栏
	fun startFGS() {
		NotificationUtil.startFGS()
	}
}

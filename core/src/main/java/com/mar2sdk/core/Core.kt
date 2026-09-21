package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.nativead.NativeAd
import com.mar2sdk.core.ad.AdIniter
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.UMPUtil
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.ad.status.ShowFailResult
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
			AdFormat.BANNER, AdFormat.NATIVE -> {
				// 嵌入式广告需要页面容器，请使用 getBanner 或 getNative。
				callback.showFailed(ShowFailResult.FAILED_TO_SHOW_CONTENT)
				AdShowStatus.SHOW_FAIL
			}
		}
	}

	/**
	 * 加载 [adIndex] 指定组的原生广告，失败或超时返回 null，并通过 [callback] 通知原因。
	 * 调用方负责把素材绑定到 NativeAdView，并在页面移除或替换广告时调用 NativeAd.destroy。
	 * showSuccess 在实际曝光时触发。建议通过页面 lifecycleScope 调用，以便离开页面时取消加载。
	 */
	suspend fun getNative(
		activity: Activity,
		callback: ShowCallback,
		adIndex: Int = 0,
	): NativeAd? = AdShower.getNative(activity, callback, adIndex)

	/**
	 * 开始异步加载 Banner，并立即返回供页面挂载的 View；无法开始加载时返回 null。
	 * 展示结果通过 [callback] 通知，showSuccess 在实际曝光时触发。
	 * 调用方负责随页面生命周期调用 View 的 pause、resume 和 destroy。
	 */
	@MainThread
	fun getBanner(
		activity: Activity,
		callback: ShowCallback,
		adSize: AdSize = AdSize.BANNER,
	): AdView? = AdShower.getBanner(activity, callback, adSize)

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

	// 初始化 Google UMP 同意状态。若近期结果已缓存，则返回 true。
	fun initConsent(activity: Activity, onComplete: (success: Boolean) -> Unit): Boolean =
		UMPUtil.initUMP(activity, onComplete)

	// 在启动流程中显示 UMP 同意表单，然后调用 [onComplete]。
	fun showSplashConsent(activity: Activity, onComplete: () -> Unit) =
		UMPUtil.showSplashUMP(activity, onComplete)

	// 打开 UMP 隐私选项表单。
	fun showPrivacyOptions(activity: Activity) = UMPUtil.showUMP(activity)

	// UMP 是否要求提供隐私选项入口。
	val isPrivacyOptionsRequired: Boolean
		get() = UMPUtil.isPrivacyOptionsRequired

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

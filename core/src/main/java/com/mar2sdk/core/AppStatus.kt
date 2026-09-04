package com.mar2sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mar2sdk.core.log.LogAppEvent
import com.mar2sdk.core.log.LogUtil

/**
 * APP状态管理类
 */
object AppStatus {
	private var registeredApplication: Application? = null
	private var startedActivityCount = 0

	// 屏幕状态 亮屏/熄屏
	var isScreenOn = false
	// 手机状态 锁屏/解锁
	var isLocked = false
	// APP是否在前台
	@Volatile
	var isForeground = false

	// 广告状态 (是/否)正在展示全屏广告
	var isShowingAd = false

	// 通知状态 (是/否正在发送通知)
	var isNotifying = false
	// 通知状态 上次发送通知的时间
	var lastNotifyTime = 0L

	@Synchronized
	fun init() {
		val application = Core.app
		if (registeredApplication === application) {
			return
		}
		registeredApplication?.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
		startedActivityCount = 0
		isForeground = false
		application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
		registeredApplication = application
	}

	// 前后台监听监控
	private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

		override fun onActivityStarted(activity: Activity) {
			if (startedActivityCount == 0) {
				LogUtil.log(LogAppEvent.app_foreground, mapOf())
				isForeground = true
			}
			startedActivityCount++
		}

		override fun onActivityResumed(activity: Activity) = Unit

		override fun onActivityPaused(activity: Activity) = Unit

		override fun onActivityStopped(activity: Activity) {
			if (startedActivityCount > 0) {
				startedActivityCount--
			}
			if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
				LogUtil.log(LogAppEvent.app_background, mapOf())
				isForeground = false
			}
		}

		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

		override fun onActivityDestroyed(activity: Activity) = Unit
	}

}

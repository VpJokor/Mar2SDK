package com.mar2sdk.impl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.common.AppObs
import java.util.concurrent.atomic.AtomicBoolean

/** 在应用从后台回到前台时请求开屏广告。 */
internal object AppOpenAdManager : Application.ActivityLifecycleCallbacks {
	private const val TAG = "AppOpenAdManager"
	private val initialized = AtomicBoolean(false)
	private var wasInBackground = false
	private var pendingOpen = false

	private val appObserver = AppObs.Listener { event ->
		if (event is AppObs.Event.ForegroundChanged) onForegroundChanged(event.isForeground)
	}

	fun init(application: Application) {
		if (!initialized.compareAndSet(false, true)) return
		AppObs.addListener(appObserver)
		application.registerActivityLifecycleCallbacks(this)
	}

	private fun onForegroundChanged(isForeground: Boolean) {
		if (isForeground) {
			if (wasInBackground) pendingOpen = true
			wasInBackground = false
		} else {
			pendingOpen = false
			wasInBackground = !AppStatus.isShowingAd
		}
	}

	override fun onActivityStarted(activity: Activity) {
		if (!pendingOpen) return
		pendingOpen = false
		if (activity is AdActivity || activity.isFinishing || activity.isDestroyed || AppStatus.isShowingAd) return
		showOpenAd(activity)
	}

	private fun showOpenAd(activity: Activity) {
		if (AdConfig.adUnits[AreaKeys.KEY_APP_FOREGROUND_OPEN]?.format != AdFormat.OPEN) return
		val context = ScreenAdContext(
			areaKey = AreaKeys.KEY_APP_FOREGROUND_OPEN,
			adFormat = AdFormat.OPEN,
			adPlatform = AdConfig.defaultPlatform,
			trigger = ScreenAdTrigger.RETURN,
		)
		try {
			AdActivity.showAd(activity, context)
		} catch (exception: RuntimeException) {
			Log.e(TAG, "Unable to show app foreground open ad", exception)
		}
	}

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
	override fun onActivityResumed(activity: Activity) = Unit
	override fun onActivityPaused(activity: Activity) = Unit
	override fun onActivityStopped(activity: Activity) = Unit
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
	override fun onActivityDestroyed(activity: Activity) = Unit
}

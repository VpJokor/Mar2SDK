package com.mar2sdk.core.log

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.notify.app.AppNotificationManager
import com.mar2sdk.core.notify.app.NotificationTriggerKey
import com.mar2sdk.core.common.DBUtil
import com.singular.sdk.Singular
import com.singular.sdk.SingularAdData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 打点的类
 */
object LogUtil {
	private const val TAG = "LogUtil"
	private const val DEFAULT_TRAFFIC_SOURCE = "unknown"
	private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val firebaseAutoCollectedAdEvents = setOf(LogAdEvent.ad_impression, LogAdEvent.ad_click)

	fun log(eventName: String, params: Map<String, Any>) {
		logInternal(eventName, withTrafficSource(params))
	}

	/**
	 * Adds the current app entry source to an event when the caller did not provide one.
	 *
	 * A non-blank value supplied by the caller is kept so callers can intentionally
	 * attribute an event to a different source (for example, an explicit campaign).
	 */
	internal fun withTrafficSource(params: Map<String, Any>): Map<String, Any> {
		val explicitSource = params[LogAdParam.traffic_source]
		if (explicitSource != null &&
			(explicitSource !is CharSequence || explicitSource.isNotBlank())
		) {
			return params
		}

		val source = UserInfo.trafficSource.takeIf { it.isNotBlank() } ?: DEFAULT_TRAFFIC_SOURCE
		return params + (LogAdParam.traffic_source to source)
	}

	private fun logInternal(eventName: String, params: Map<String, Any>) {
		if (Core.appMod == AppMod.DEBUG || Core.appMod == AppMod.TEST || Core.appMod == AppMod.PRE_RELEASE) {
			Log.e(TAG, "log: $eventName ${formatParams(params)}")
		}
		try {
			spUse(eventName, params)
		} catch (exception: Exception) {
			Log.e(TAG, "spUse error", exception)
		}
		if (shouldLogFirebase(eventName, params) && LogConfig.isEnabled(LogConfig.fbEvents, eventName)) {
			try {
				logFirebase(eventName, params)
			} catch (exception: Exception) {
				Log.e(TAG, "logFirebase error", exception)
			}
		}
		if (LogConfig.isEnabled(LogConfig.thEvents, eventName)) logThinking(eventName, params)
		if (LogConfig.isEnabled(LogConfig.localEvents, eventName)) logLocal(eventName, params)
		if (LogConfig.isEnabled(LogConfig.netEvents, eventName)) logNet(eventName, params)
	}

	fun spUse(eventName: String, params: Map<String, Any>) {
		val eventParams = withTrafficSource(params)
		if (eventName == LogAdEvent.ad_revenue) {
			if (
				(eventParams[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.OPEN.name) ||
				(eventParams[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.INTER.name) ||
				(eventParams[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.VIDEO.name)
			) {
				if (UserInfo.firstAdRevenue == -1.0) {
					UserInfo.firstAdRevenue = (eventParams[FirebaseAnalytics.Param.VALUE] as? Number)?.toDouble() ?: -1.0
					Core.setUserOnceAttr("firstAdRevenue", UserInfo.firstAdRevenue.toString())
					UserInfo.saveUserInfo()
					RiskUtil.judgeRisk()
				}
			}
		}
		val notificationScene = when (eventName) {
			LogAdEvent.ad_click -> NotificationTriggerKey.ad_click
			LogAppEvent.app_exit -> NotificationTriggerKey.app_exit
			else -> null
		}
		if (notificationScene != null) {
			notificationScope.launch {
				try {
					AppNotificationManager.addBatch(notificationScene)
				} catch (exception: CancellationException) {
					throw exception
				} catch (exception: Exception) {
					Log.e(TAG, "Failed to enqueue $notificationScene notification batch", exception)
				}
			}
		}
	}

	/**
	 * 接入 Firebase Analytics 后，AdMob 会自动上报这些标准事件。
	 * 其他已启用的渠道继续接收事件，手动回调不再重复发送到 Firebase。
	 */
	private fun shouldLogFirebase(eventName: String, params: Map<String, Any>): Boolean {
		val isAdMobEvent = params[LogAdParam.ad_platform] == AdPlatform.ADMOB.name
		return !(isAdMobEvent && eventName in firebaseAutoCollectedAdEvents)
	}

	/** 上报 Firebase Analytics 事件，并把 Map 参数转换为 Bundle。 */
	fun logFirebase(eventName: String, params: Map<String, Any>) {
		val eventParams = withTrafficSource(params)
		val firebaseAnalytics = FirebaseAnalytics.getInstance(Core.app)
		val bundle = Bundle()
		for ((key, value) in eventParams) {
			when (value) {
				is String -> bundle.putString(key, value)
				is Int -> bundle.putInt(key, value)
				is Long -> bundle.putLong(key, value)
				is Double -> bundle.putDouble(key, value)
				is Float -> bundle.putFloat(key, value)
				is Boolean -> bundle.putBoolean(key, value)
				else -> bundle.putString(key, value.toString())
			}
		}
		firebaseAnalytics.logEvent(eventName, bundle)
	}

	fun logThinking(eventName: String, params: Map<String, Any>) {
		try {
			ThinkingUtil.log(eventName, withTrafficSource(params))
		} catch (e: Exception) {
			Log.e(TAG, "logThinking error: ${e.message}")
		}
	}

	// 本地打点
	fun logLocal(eventName: String, params: Map<String, Any>) {
		val eventTimeMillis = System.currentTimeMillis()
		try {
			val eventParams = withTrafficSource(params)
			val jsonObject = JSONObject()
			for ((key, value) in eventParams) {
				jsonObject.put(key, value)
			}
			DBUtil.insertLog(eventName, jsonObject.toString(), eventTimeMillis)
		} catch (exception: Exception) {
			Log.e(TAG, "logLocal error", exception)
		}
	}

	// 打点到自己的服务端
	fun logNet(eventName: String, params: Map<String, Any>) {
		AdEventReporter.capture(eventName, withTrafficSource(params))
	}

	fun logSingularAdRevenue(adPlatform: String, revenue: Double) {
		logSingularAdRevenue(adPlatform, revenue, null)
	}

	fun logSingularAdRevenue(adContext: ScreenAdContext, revenue: Double) {
		logSingularAdRevenue(adContext.adPlatform.name, revenue, adContext)
	}

	private fun logSingularAdRevenue(adPlatform: String, revenue: Double, adContext: ScreenAdContext?) {
		if (!SingularConfig.trackRevenue) {
			return
		}
		try {
			if (revenue > 0) {
				val adData = adContext?.toSingularAdData(revenue)
					?: SingularAdData(adPlatform, LogAdParam.USD, revenue)
				Singular.adRevenue(adData)
			}
		} catch (e: Exception) {
			Log.e(TAG, "logSingularAdRevenue error: ${e.message}")
		}
	}

	fun formatParams(params: Map<String, Any?>): String = params.entries.joinToString(", ", "{", "}") { (key, value) -> "$key=$value" }
}

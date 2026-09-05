package com.mar2sdk.core.log

import android.os.Bundle
import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.util.DBUtil
import com.singular.sdk.Singular
import com.singular.sdk.SingularAdData
import org.json.JSONObject

/**
 * 打点的类
 */
object LogUtil {
	private const val TAG = "LogUtil"
	fun log(eventName: String, params: Map<String, Any>) {
		if (Core.appMod == AppMod.DEBUG || Core.appMod == AppMod.TEST || Core.appMod == AppMod.PRE_RELEASE) {
			Log.e(TAG, "log: $eventName ${formatParams(params)}")
		}
		try {
			logFirebase(eventName, params)
		} catch (exception: Exception) {
			Log.e(TAG, "logFirebase error", exception)
		}
		logThinking(eventName, params)
		logLocal(eventName, params)
		logNet(eventName, params)
		if (eventName == LogAdEvent.ad_revenue) {
			if (
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.OPEN.name) ||
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.INTER.name) ||
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(AdFormat.VIDEO.name)
			) {
				if (UserInfo.firstAdRevenue == -1.0) {
					UserInfo.firstAdRevenue = (params[FirebaseAnalytics.Param.VALUE] as? Number)?.toDouble() ?: -1.0
					ThinkingUtil.setUserOnceAttr("firstAdRevenue", UserInfo.firstAdRevenue.toString())
					UserInfo.saveUserInfo()
					RiskUtil.judgeRisk()
				}
			}
		}
	}

	/** 上报 Firebase Analytics 事件，并把 Map 参数转换为 Bundle。 */
	fun logFirebase(eventName: String, params: Map<String, Any>) {
		val firebaseAnalytics = FirebaseAnalytics.getInstance(Core.app)
		val bundle = Bundle()
		for ((key, value) in params) {
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
		// 打点截止时间判断
		val logDurationMillis = ThinkingConfig.logEndTime.toLong() * 60 * 60 * 1000L
		if (ThinkingConfig.logEndTime <= 0 || System.currentTimeMillis() - UserInfo.firstOpenTime >= logDurationMillis) return

		try {
			val jsonObject = JSONObject()
			for ((key, value) in params) {
				jsonObject.put(key, value)
			}
			TDAnalytics.track(eventName, jsonObject)
		} catch (e: Exception) {
			Log.e(TAG, "logThinking error: ${e.message}")
		}
	}

	// 本地打点
	fun logLocal(eventName: String, params: Map<String, Any>) {
		val eventTimeMillis = System.currentTimeMillis()
		try {
			val jsonObject = JSONObject()
			for ((key, value) in params) {
				jsonObject.put(key, value)
			}
			DBUtil.insertLog(eventName, jsonObject.toString(), eventTimeMillis)
		} catch (exception: Exception) {
			Log.e(TAG, "logLocal error", exception)
		}
	}

	// 打点到自己的服务端
	fun logNet(eventName: String, params: Map<String, Any>) {

	}

	fun logSingularAdRevenue(adPlatform: String, revenue: Double) {
		if (!SingularConfig.trackRevenue) {
			return
		}
		try {
			if (revenue > 0) {
				Singular.adRevenue(SingularAdData(adPlatform, LogAdParam.USD, revenue))
			}
		} catch (e: Exception) {
			Log.e(TAG, "logSingularAdRevenue error: ${e.message}")
		}
	}

	fun formatParams(params: Map<String, Any?>): String = params.entries.joinToString(", ", "{", "}") { (key, value) -> "$key=$value" }
}

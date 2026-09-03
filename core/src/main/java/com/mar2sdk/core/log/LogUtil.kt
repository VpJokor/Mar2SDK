package com.mar2sdk.core.log

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.policy.RiskUtil
import com.singular.sdk.Singular
import com.singular.sdk.SingularAdData
import org.json.JSONObject

/**
 * 打点的类
 */
object LogUtil {
	private const val TAG = "LogUtil"
	fun log(eventName: String, params: Map<String, Any>) {
		if (Core.appMod == AppMod.TEST || Core.appMod == AppMod.PRE_RELEASE) {
			Log.e(TAG, "log: $eventName ${formatParams(params)}")
		}
		logFirebase(eventName, params)
		logThinking(eventName, params)
		
		if (eventName == LogAdEvent.ad_revenue) {
			if (
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(LogAdParam.ad_format_open) ||
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(LogAdParam.ad_format_inter) ||
				(params[FirebaseAnalytics.Param.AD_FORMAT] as? String).equals(LogAdParam.ad_format_video)
			) {
				RiskUtil.judgeEcpm((params[FirebaseAnalytics.Param.VALUE] as? Number)?.toDouble() ?: 0.0)
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

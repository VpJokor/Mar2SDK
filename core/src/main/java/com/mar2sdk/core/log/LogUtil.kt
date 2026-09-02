package com.mar2sdk.core.log

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core

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

	fun formatParams(params: Map<String, Any?>): String = params.entries.joinToString(", ", "{", "}") { (key, value) -> "$key=$value" }
}

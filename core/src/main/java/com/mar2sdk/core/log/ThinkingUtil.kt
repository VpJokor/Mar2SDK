package com.mar2sdk.core.log

import android.util.Log
import cn.thinkingdata.analytics.TDAnalytics
import cn.thinkingdata.analytics.TDConfig
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.UserInfo
import org.json.JSONObject

object ThinkingUtil {
	private const val TAG = "ThinkingUtil"
	fun init() {
		val config = TDConfig.getInstance(Core.app, ThinkingConfig.key, ThinkingConfig.url)
		val mod = if(Core.appMod == AppMod.DEBUG || Core.appMod == AppMod.TEST || Core.appMod == AppMod.PRE_RELEASE) {
			TDConfig.TDMode.DEBUG
		}  else {
			TDConfig.TDMode.NORMAL
		}
		config.setMode(mod)
		TDAnalytics.init(config)
		TDAnalytics.enableAutoTrack(
			TDAnalytics.TDAutoTrackEventType.APP_START or
					TDAnalytics.TDAutoTrackEventType.APP_END or
					TDAnalytics.TDAutoTrackEventType.APP_INSTALL
		)
	}

	// 日志打点
	fun log(eventName: String, params: Map<String, Any>) {
		// 打点截止时间判断
		if (!isWithinLogWindow()) return

		val jsonObject = JSONObject()
		for ((key, value) in params) {
			jsonObject.put(key, value)
		}
		TDAnalytics.track(eventName, jsonObject)
	}

	// 设置可覆盖的用户属性。
	fun setUserAttr(key: String, value: Any) {
		if (!isWithinLogWindow()) return
		Log.e(TAG, "setUserOnceAttr: key = $key, value = $value" )
		val userProperties = JSONObject()
		userProperties.put(key, value)
		TDAnalytics.userSet(userProperties)
	}

	// 设置只写一次的用户属性。
	fun setUserOnceAttr(key: String, value: String) {
		if (!isWithinLogWindow()) return
		Log.e(TAG, "setUserOnceAttr: key = $key, value = $value" )
		val userProperties = JSONObject()
		userProperties.put(key, value)
		TDAnalytics.userSetOnce(userProperties)
	}

	// 设置事件属性
	fun setEventAttr(key: String, value: Any) {
		if (!isWithinLogWindow()) return
		Log.e(TAG, "setEventAttr: key = $key, value = $value")
		val eventProperties = JSONObject()
		eventProperties.put(key, value)
		TDAnalytics.setSuperProperties(eventProperties)
	}

	// 判断 ThinkingData 是否仍允许接收 SDK 日志。
	internal fun isWithinLogWindow(nowMillis: Long = System.currentTimeMillis()): Boolean {
		val logEndTimeHours = ThinkingConfig.logEndTime
		if (logEndTimeHours <= 0 || UserInfo.firstOpenTime <= 0L) return false

		val logDurationMillis = logEndTimeHours.toLong() * MILLIS_PER_HOUR
		return nowMillis - UserInfo.firstOpenTime < logDurationMillis
	}

	private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
}

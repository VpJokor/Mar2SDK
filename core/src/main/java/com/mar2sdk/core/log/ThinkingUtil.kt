package com.mar2sdk.core.log

import cn.thinkingdata.analytics.TDAnalytics
import cn.thinkingdata.analytics.TDConfig
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import org.json.JSONObject

object ThinkingUtil {
	fun init() {
		val config = TDConfig.getInstance(Core.app, ThinkingConfig.key, ThinkingConfig.url)
		config.setMode(if(Core.appMod == AppMod.TEST || Core.appMod == AppMod.PRE_RELEASE) TDConfig.TDMode.DEBUG else TDConfig.TDMode.NORMAL)
		TDAnalytics.init(config)
		TDAnalytics.enableAutoTrack(
			TDAnalytics.TDAutoTrackEventType.APP_START or
					TDAnalytics.TDAutoTrackEventType.APP_END or
					TDAnalytics.TDAutoTrackEventType.APP_INSTALL
		)
	}

	// 设置可覆盖的用户属性。
	fun setUserAttr(key: String, value: Any) {
		val userProperties =  JSONObject()
		userProperties.put(key, value)
		TDAnalytics.userSet(userProperties);
	}

	// 设置只写一次的用户属性。
	fun setUserOnceAttr(key: String, value: String) {
		val userProperties =  JSONObject()
		userProperties.put(key, value)
		TDAnalytics.userSetOnce(userProperties);
	}

}
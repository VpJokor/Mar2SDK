package com.mar2sdk.core

import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.impl.admob.AdmobConfig
import com.mar2sdk.core.ad.impl.max.MaxConfig
import com.mar2sdk.core.ad.impl.topon.ToponConfig
import com.mar2sdk.core.ad.impl.tradplus.TradplusConfig
import com.mar2sdk.core.ad.impl.unity.UnityConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.risk.PolicyConfig
import com.mar2sdk.core.risk.UserInfo
import com.mar2sdk.core.util.DBUtil
import com.mar2sdk.core.util.PreferenceUtil

/**
 * 配置相关的初始化器
 */
object Config {

	fun initConfig() {
		PreferenceUtil.init()
		DBUtil.init()
		initUserInfo()
		initPolicyConfig()
		initAdConfig()
		initFirebaseConfig()
		initSingularConfig()
		initThinkingConfig()
		initNotificationConfig()
	}

	fun initUserInfo() {
		UserInfo.init()
	}

	fun initPolicyConfig() {
		PolicyConfig.init()
	}

	fun initAdConfig() {
		AdConfig.init()
		AdmobConfig.init()
		MaxConfig.init()
		ToponConfig.init()
		TradplusConfig.init()
		UnityConfig.init()
	}

	fun initFirebaseConfig() {

	}

	fun initSingularConfig() {
		SingularConfig.init()
	}

	fun initThinkingConfig() {
		ThinkingConfig.init()
	}

	fun initNotificationConfig() {
		NotificationConfig.init()
	}
}

package com.mar2sdk.core

import com.mar2sdk.core.ad.impl.AdmobConfig
import com.mar2sdk.core.firebase.FirebaseConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.policy.PolicyConfig
import com.mar2sdk.core.policy.RiskUtil
import com.mar2sdk.core.policy.UserInfo
import com.mar2sdk.core.util.PreferenceUtil

/**
 * 配置相关的初始化器
 */
object Config {

	fun initConfig() {
		PreferenceUtil.init(Core.app)
		initUserInfo()
		initPolicyConfig()
		initAdmobConfig()
		initFirebaseConfig()
		initSingularConfig()
		initThinkingConfig()
	}

	fun initUserInfo() {
		UserInfo.init()
	}

	fun initPolicyConfig() {
		PolicyConfig.init()
		RiskUtil.init()
	}

	fun initAdmobConfig() {
		AdmobConfig.init()
	}

	fun initFirebaseConfig() {
		FirebaseConfig
	}

	fun initSingularConfig() {
		SingularConfig.init()
	}

	fun initThinkingConfig() {
		ThinkingConfig
	}
}
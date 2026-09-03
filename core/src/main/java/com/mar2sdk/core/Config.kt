package com.mar2sdk.core

import com.mar2sdk.core.ad.impl.AdmobConfig
import com.mar2sdk.core.firebase.FirebaseConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.policy.PolicyConfig
import com.mar2sdk.core.util.PreferenceUtil

/**
 * 配置相关的初始化器
 */
object Config {

	fun initConfig() {
		PreferenceUtil.init(Core.app)
		initPolicyConfig()
		initAdmobConfig()
		initFirebaseConfig()
		initSingularConfig()
		initThinkingConfig()
	}

	fun initPolicyConfig() {
		PolicyConfig
	}

	fun initAdmobConfig() {
		AdmobConfig
	}

	fun initFirebaseConfig() {
		FirebaseConfig
	}

	fun initSingularConfig() {
		SingularConfig
	}

	fun initThinkingConfig() {
		ThinkingConfig
	}
}
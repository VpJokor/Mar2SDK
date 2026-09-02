package com.mar2sdk.core

import com.mar2sdk.core.ad.impl.AdmobConfig
import com.mar2sdk.core.firebase.FirebaseConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig

/**
 * 配置相关的初始化器
 */
object Config {

	fun initConfig() {
		initAdmobConfig()
		initFirebaseConfig()
		initSingularConfig()
		initThinkingConfig()
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
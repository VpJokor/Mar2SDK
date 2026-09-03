package com.mar2sdk.core.ad.impl

import com.mar2sdk.core.ad.impl.AdmobConfig.loadConfigFromPreference
import com.mar2sdk.core.ad.impl.AdmobConfig.loadConfigFromRaw

object MaxConfig {
	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	fun loadConfigFromRaw(){

	}

	fun loadConfigFromPreference(){

	}

	fun saveMaxConfig() {
	}

}
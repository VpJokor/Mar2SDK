package com.mar2sdk.core.policy

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONObject

//Policy的配置
object PolicyConfig {
	// 高ecpm阈值
	var highEcpm = 10.0
	// 服务端的Url
	var serverUrl = "https://api.newminigame.online"

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.policy_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			highEcpm = getDouble("highEcpm")
			serverUrl = getString("serverUrl")
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(PolicyKey) {
			highEcpm = PreferenceUtil.getDouble(KEY_HIGH_ECPM, highEcpm)
			serverUrl = PreferenceUtil.getString(KEY_SERVER_URL, serverUrl)
		}
	}

	// 把配置保存到本地 (Preference)。
	fun savePolicyConfig() {
		with(PolicyKey) {
			PreferenceUtil.commitDouble(KEY_HIGH_ECPM, highEcpm)
			PreferenceUtil.commitString(KEY_SERVER_URL, serverUrl)
		}
	}
}

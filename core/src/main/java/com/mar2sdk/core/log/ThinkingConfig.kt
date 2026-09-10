package com.mar2sdk.core.log

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONObject

//Thinking的配置
object ThinkingConfig {
	var key = ""
	var url = ""

	// 用户首次打开APP后多久不再上报日志，单位小时(默认48小时)
	var logEndTime = 48

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.thinking_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			key = getString("key")
			url = getString("url")
			logEndTime = getInt("logEndTime")
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(ThinkingKey) {
			key = PreferenceUtil.getString(KEY_KEY, key)
			url = PreferenceUtil.getString(KEY_URL, url)
			logEndTime = PreferenceUtil.getInt(KEY_LOG_END_TIME, logEndTime)
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveThinkingConfig() {
		with(ThinkingKey) {
			PreferenceUtil.commitString(KEY_KEY, key)
			PreferenceUtil.commitString(KEY_URL, url)
			PreferenceUtil.commitInt(KEY_LOG_END_TIME, logEndTime)
		}
	}
}

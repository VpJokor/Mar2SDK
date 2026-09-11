package com.mar2sdk.core.firebase

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONObject

//Singular的配置
object SingularConfig {
	var key = ""
	var secret = ""
	// 是否给Singular上报收入事件
	var trackRevenue = true

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.singular_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			key = getString("key")
			secret = getString("secret")
			trackRevenue = getBoolean("trackRevenue")
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(SingularKey) {
			key = PreferenceUtil.getString(KEY_KEY, key)
			secret = PreferenceUtil.getString(KEY_SECRET, secret)
			trackRevenue = PreferenceUtil.getBoolean(KEY_TRACK_REVENUE, trackRevenue)
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveSingularConfig() {
		with(SingularKey) {
			PreferenceUtil.commitString(KEY_KEY, key)
			PreferenceUtil.commitString(KEY_SECRET, secret)
			PreferenceUtil.commitBoolean(KEY_TRACK_REVENUE, trackRevenue)
		}
	}

	internal fun applyConfig(config: JSONObject) {
		key = config.optString("key", key)
		secret = config.optString("secret", secret)
		trackRevenue = config.optBoolean("trackRevenue", trackRevenue)
		saveSingularConfig()
	}
}

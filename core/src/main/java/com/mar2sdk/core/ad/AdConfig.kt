package com.mar2sdk.core.ad

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONObject

//广告配置
object AdConfig {
	var adPlatform = AdPlatform.ADMOB

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.ad_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			adPlatform = AdPlatform.valueOf(getString("adPlatform"))
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(AdKey) {
			adPlatform = AdPlatform.valueOf(
				PreferenceUtil.getString(KEY_AD_PLATFORM, adPlatform.name)
			)
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveAdConfig() {
		with(AdKey) {
			PreferenceUtil.commitString(KEY_AD_PLATFORM, adPlatform.name)
		}
	}
}

package com.mar2sdk.core.ad

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

//广告配置
object AdConfig {

	var defaultPlatform = AdPlatform.ADMOB
	var activePlatforms = mutableSetOf<AdPlatform>()

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
			defaultPlatform = AdPlatform.valueOf(getString("defaultPlatform"))
			activePlatforms = getJSONArray("activePlatforms").toAdPlatforms()
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(AdKey) {
			defaultPlatform = AdPlatform.valueOf(
				PreferenceUtil.getString(KEY_DEFAULT_PLATFORM, defaultPlatform.name)
			)
			activePlatforms = JSONArray(
				PreferenceUtil.getString(KEY_ACTIVE_PLATFORMS, activePlatforms.toJson())
			).toAdPlatforms()
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveAdConfig() {
		with(AdKey) {
			PreferenceUtil.commitString(KEY_DEFAULT_PLATFORM, defaultPlatform.name)
			PreferenceUtil.commitString(KEY_ACTIVE_PLATFORMS, activePlatforms.toJson())
		}
	}

	private fun JSONArray.toAdPlatforms(): MutableSet<AdPlatform> =
		(0 until length()).mapTo(mutableSetOf()) { index ->
			AdPlatform.valueOf(getString(index))
		}

	private fun Set<AdPlatform>.toJson(): String =
		JSONArray(map { it.name }).toString()
}

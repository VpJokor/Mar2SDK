package com.mar2sdk.core.ad

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

// 广告配置
object AdConfig {

	data class AdUnitConfig(
		val rate: Int,
		val maxPerHour: Int,
		val maxPerDay: Int,
		val intervalSeconds: Int
	)

	var defaultPlatform = AdPlatform.ADMOB
	var activePlatforms = mutableSetOf<AdPlatform>()
	var isOpen = true
	// 广告展示超时时间，单位毫秒
	var showMaxTime = 10 * 1000L
	// 广告展示前最小等待时间，单位毫秒
	var showMinTime = 500L
	var showMod = 555
	var showMaxCount = 50
	// 广告位的具体配置
	var adUnits = mapOf<String, AdUnitConfig>()

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.ad_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			defaultPlatform = AdPlatform.valueOf(getString("defaultPlatform"))
			activePlatforms = getJSONArray("activePlatforms").toAdPlatforms()
			isOpen = optBoolean("isOpen", isOpen)
			showMaxTime = optLong("showMaxTime", showMaxTime)
			showMinTime = optLong("showMinTime", showMinTime)
			showMod = optInt("showMod", showMod)
			showMaxCount = optInt("showMaxCount", showMaxCount)
			adUnits = optJSONObject("ad_units")?.toAdUnits() ?: adUnits
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值
	fun loadConfigFromPreference() {
		with(AdKey) {
			defaultPlatform = AdPlatform.valueOf(
				PreferenceUtil.getString(KEY_DEFAULT_PLATFORM, defaultPlatform.name)
			)
			activePlatforms = JSONArray(
				PreferenceUtil.getString(KEY_ACTIVE_PLATFORMS, activePlatforms.toJson())
			).toAdPlatforms()
			isOpen = PreferenceUtil.getBoolean(KEY_IS_OPEN, isOpen)
			showMaxTime = PreferenceUtil.getLong(KEY_SHOW_MAX_TIME, showMaxTime)
			showMinTime = PreferenceUtil.getLong(KEY_SHOW_MIN_TIME, showMinTime)
			showMod = PreferenceUtil.getInt(KEY_SHOW_MOD, showMod)
			showMaxCount = PreferenceUtil.getInt(KEY_SHOW_MAX_COUNT, showMaxCount)
			adUnits = JSONObject(
				PreferenceUtil.getString(KEY_AD_UNITS, adUnits.toJson())
			).toAdUnits()
		}
	}

	// 把配置保存到本地 (Preference)
	fun saveAdConfig() {
		with(AdKey) {
			PreferenceUtil.commitString(KEY_DEFAULT_PLATFORM, defaultPlatform.name)
			PreferenceUtil.commitString(KEY_ACTIVE_PLATFORMS, activePlatforms.toJson())
			PreferenceUtil.commitBoolean(KEY_IS_OPEN, isOpen)
			PreferenceUtil.commitLong(KEY_SHOW_MAX_TIME, showMaxTime)
			PreferenceUtil.commitLong(KEY_SHOW_MIN_TIME, showMinTime)
			PreferenceUtil.commitInt(KEY_SHOW_MOD, showMod)
			PreferenceUtil.commitInt(KEY_SHOW_MAX_COUNT, showMaxCount)
			PreferenceUtil.commitString(KEY_AD_UNITS, adUnits.toJson())
		}
	}

	private fun JSONArray.toAdPlatforms(): MutableSet<AdPlatform> =
		(0 until length()).mapTo(mutableSetOf()) { index ->
			AdPlatform.valueOf(getString(index))
		}

	private fun Set<AdPlatform>.toJson(): String =
		JSONArray(map { it.name }).toString()

	private fun JSONObject.toAdUnits(): Map<String, AdUnitConfig> =
		keys().asSequence().associateWith { key ->
			getJSONObject(key).let { config ->
				AdUnitConfig(
					config.optInt("rate"),
					config.optInt("max_per_hour"),
					config.optInt("max_per_day"),
					config.optInt("interval_seconds")
				)
			}
		}

	private fun Map<String, AdUnitConfig>.toJson(): String =
		JSONObject().apply {
			forEach { (key, config) ->
				put(key, JSONObject().apply {
					put("rate", config.rate)
					put("max_per_hour", config.maxPerHour)
					put("max_per_day", config.maxPerDay)
					put("interval_seconds", config.intervalSeconds)
				})
			}
		}.toString()
}

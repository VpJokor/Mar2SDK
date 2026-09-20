package com.mar2sdk.core.ad

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

// 广告比价模式
enum class ShowMod {
	MODE_555,
	MODE_666,
	MODE_777,
	MODE_888
}

// rate: 广告展示概率
// max1H：每小时最大展示数
// max24H：滚动24小时最大展示数
// interval：与上次展示的时间间隔，单位秒
// format：广告展示类型
// fromRoutes：允许展示广告的来源路由
// toRoutes：允许展示广告的目标路由
data class AdUnitConfig(
	val rate: Double,
	val max1H: Int,
	val max24H: Int,
	val interval: Int,
	val format: AdFormat,
	val fromRoutes: List<String>,
	val toRoutes: List<String>
)

// 广告配置
object AdConfig {

	var defaultPlatform = AdPlatform.ADMOB
	var activePlatforms = mutableSetOf<AdPlatform>()
	var explorePlatforms = AdPlatform.TRADPLUS
	// 全局广告总开关
	var isOpen = true
	// 广告展示超时时间，单位毫秒
	var showMaxTime = 10 * 1000L
	// 广告展示前最小等待时间，单位毫秒
	var showMinTime = 500L
	// 广告展示模式
	var showMod = ShowMod.MODE_555
	// 1小时内最大展示数
	var max1H = 50
	// 24小时内最大展示数
	var max24H = 50
	// 全局广告两次展示之间的最短间隔，单位秒
	var interval = 0
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
			explorePlatforms = AdPlatform.valueOf(getString("explorePlatforms"))
			isOpen = optBoolean("isOpen", isOpen)
			showMaxTime = optLong("showMaxTime", showMaxTime)
			showMinTime = optLong("showMinTime", showMinTime)
			showMod = ShowMod.valueOf(getString("showMod"))
			max1H = optInt("1HMax", max1H)
			max24H = optInt("24HMax", max24H)
			interval = optInt("interval", interval)
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
			explorePlatforms = AdPlatform.valueOf(
				PreferenceUtil.getString(KEY_EXPLORE_PLATFORMS, explorePlatforms.name)
			)
			isOpen = PreferenceUtil.getBoolean(KEY_IS_OPEN, isOpen)
			showMaxTime = PreferenceUtil.getLong(KEY_SHOW_MAX_TIME, showMaxTime)
			showMinTime = PreferenceUtil.getLong(KEY_SHOW_MIN_TIME, showMinTime)
			showMod = ShowMod.valueOf(PreferenceUtil.getString(KEY_SHOW_MOD, showMod.name))
			max1H = PreferenceUtil.getInt(KEY_1H_MAX, max1H)
			max24H = PreferenceUtil.getInt(KEY_24H_MAX, max24H)
			interval = PreferenceUtil.getInt(KEY_INTERVAL, interval)
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
			PreferenceUtil.commitString(KEY_EXPLORE_PLATFORMS, explorePlatforms.name)
			PreferenceUtil.commitBoolean(KEY_IS_OPEN, isOpen)
			PreferenceUtil.commitLong(KEY_SHOW_MAX_TIME, showMaxTime)
			PreferenceUtil.commitLong(KEY_SHOW_MIN_TIME, showMinTime)
			PreferenceUtil.commitString(KEY_SHOW_MOD, showMod.name)
			PreferenceUtil.commitInt(KEY_1H_MAX, max1H)
			PreferenceUtil.commitInt(KEY_24H_MAX, max24H)
			PreferenceUtil.commitInt(KEY_INTERVAL, interval)
			PreferenceUtil.commitString(KEY_AD_UNITS, adUnits.toJson())
		}
	}

	/** 应用 Remote Config 的 JSON 配置，并保存到本地供下次启动使用。 */
	internal fun applyConfig(config: JSONObject) {
		with(config) {
			if (has("defaultPlatform")) defaultPlatform = AdPlatform.valueOf(getString("defaultPlatform"))
			if (has("activePlatforms")) activePlatforms = getJSONArray("activePlatforms").toAdPlatforms()
			if (has("explorePlatforms")) explorePlatforms = AdPlatform.valueOf(getString("explorePlatforms"))
			isOpen = optBoolean("isOpen", isOpen)
			showMaxTime = optLong("showMaxTime", showMaxTime)
			showMinTime = optLong("showMinTime", showMinTime)
			if (has("showMod")) showMod = ShowMod.valueOf(getString("showMod"))
			max1H = optInt("1HMax", max1H)
			max24H = optInt("24HMax", max24H)
			interval = optInt("interval", interval)
			optJSONObject("ad_units")?.let { adUnits = it.toAdUnits() }
		}
		saveAdConfig()
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
					config.optDouble("rate"),
					config.optInt("1HMax"),
					config.optInt("24HMax"),
					config.optInt("interval"),
					AdFormat.valueOf(config.getString("format")),
					config.optJSONArray("fromRoutes")?.toStringList() ?: emptyList(),
					config.optJSONArray("toRoutes")?.toStringList() ?: emptyList()
				)
			}
		}

	private fun JSONArray.toStringList(): List<String> =
		(0 until length()).map { index -> getString(index) }

	private fun Map<String, AdUnitConfig>.toJson(): String =
		JSONObject().apply {
			forEach { (key, config) ->
				put(key, JSONObject().apply {
					put("rate", config.rate)
					put("1HMax", config.max1H)
					put("24HMax", config.max24H)
					put("interval", config.interval)
					put("format", config.format.name)
					put("fromRoutes", JSONArray(config.fromRoutes))
					put("toRoutes", JSONArray(config.toRoutes))
				})
			}
		}.toString()
}

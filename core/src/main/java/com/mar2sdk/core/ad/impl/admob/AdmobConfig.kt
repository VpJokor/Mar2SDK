package com.mar2sdk.core.ad.impl.admob

import androidx.annotation.MainThread
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject


// id：正式广告位 ID
// timeout：广告缓存有效期，单位毫秒
// poolSize：广告缓存池容量
// probeConfig：探价配置
data class AdUnitConfig(
	val id: String,
	val timeout: Long,
	val poolSize: Int,
	val probeConfig: ProbeConfig
)

// 广告探价模式
// REFLECT 反射取价
// ADAPTER_H 探针向上取价
// ADAPTER_M 探针居中取价
// ADAPTER_L 探针向下取价
enum class ProbeMod {
	REFLECT,
	ADAPTER_H,
	ADAPTER_M,
	ADAPTER_L,
}

// mod：探价模式
// timeout：探价超时时间，单位毫秒
// currency：探价币种
// instances：探价实例
data class ProbeConfig(
	val mod: ProbeMod,
	val timeout: Long,
	val currency: String,
	val instances: List<ProbeInstance>
)

data class ProbeInstance(
	val instanceId: String,
	val label: String,
	val ecpm: Double,
	val param: String
)

// id：Banner 正式广告位 ID
data class BannerConfig(
	val id: String
)

// timeout：原生广告超时时间，单位毫秒
// ads：原生广告位配置列表
data class NativeConfig(
	val timeout: Long,
	val ads: List<NativeAdConfig>
)

// 高、中、低三档正式广告位 ID
data class NativeAdConfig(
	val hID: String,
	val mID: String,
	val lID: String
)

// AdMob 广告配置
object AdmobConfig {

	val testOpenID = "ca-app-pub-3940256099942544/9257395921"
	val testInterID = "ca-app-pub-3940256099942544/1033173712"
	val testVideoID = "ca-app-pub-3940256099942544/5224354917"
	val testBannerID = "ca-app-pub-3940256099942544/6300978111"
	val testNativeID = "ca-app-pub-3940256099942544/2247696110"

	var openConfig = AdUnitConfig("", 12600000L, 1, ProbeConfig(ProbeMod.REFLECT, 3000L, "USD", emptyList()))
	var interConfig = AdUnitConfig("", 3000000L, 1, ProbeConfig(ProbeMod.REFLECT, 3000L, "USD", emptyList()))
	var videoConfig = AdUnitConfig("", 3000000L, 1, ProbeConfig(ProbeMod.REFLECT, 3000L, "USD", emptyList()))
	var bannerConfig = BannerConfig("")
	var nativeConfig = NativeConfig(30000L, emptyList())

	private val isTest: Boolean
		get() = Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG

	val openID: String
		get() = if (isTest) testOpenID else openConfig.id
	val interID: String
		get() = if (isTest) testInterID else interConfig.id
	val videoID: String
		get() = if (isTest) testVideoID else videoConfig.id
	val bannerID: String
		get() = if (isTest) testBannerID else bannerConfig.id

	/** 按高、中、低顺序返回指定组的有效原生广告位；测试模式使用官方测试广告位。 */
	fun nativeIDs(adIndex: Int): List<String> {
		if (adIndex < 0) return emptyList()
		if (isTest) return listOf(testNativeID)
		val ad = nativeConfig.ads.getOrNull(adIndex) ?: return emptyList()
		return listOf(ad.hID, ad.mID, ad.lID).filter { it.isNotBlank() }
	}

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.admob_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			openConfig = getJSONObject("openConfig").toAdUnitConfig()
			interConfig = getJSONObject("interConfig").toAdUnitConfig()
			videoConfig = getJSONObject("videoConfig").toAdUnitConfig()
			bannerConfig = getJSONObject("bannerConfig").toBannerConfig()
			nativeConfig = getJSONObject("nativeConfig").toNativeConfig()
		}
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值
	fun loadConfigFromPreference() {
		with(AdmobKey) {
			openConfig = JSONObject(
				PreferenceUtil.getString(KEY_OPEN_CONFIG, openConfig.toJson())
			).toAdUnitConfig()
			interConfig = JSONObject(
				PreferenceUtil.getString(KEY_INTER_CONFIG, interConfig.toJson())
			).toAdUnitConfig()
			videoConfig = JSONObject(
				PreferenceUtil.getString(KEY_VIDEO_CONFIG, videoConfig.toJson())
			).toAdUnitConfig()
			bannerConfig = JSONObject(
				PreferenceUtil.getString(KEY_BANNER_CONFIG, bannerConfig.toJson())
			).toBannerConfig()
			nativeConfig = JSONObject(
				PreferenceUtil.getString(KEY_NATIVE_CONFIG, nativeConfig.toJson())
			).toNativeConfig()
		}
	}

	// 把配置保存到本地 (Preference)
	fun saveAdmobConfig() {
		with(AdmobKey) {
			PreferenceUtil.commitString(KEY_OPEN_CONFIG, openConfig.toJson())
			PreferenceUtil.commitString(KEY_INTER_CONFIG, interConfig.toJson())
			PreferenceUtil.commitString(KEY_VIDEO_CONFIG, videoConfig.toJson())
			PreferenceUtil.commitString(KEY_BANNER_CONFIG, bannerConfig.toJson())
			PreferenceUtil.commitString(KEY_NATIVE_CONFIG, nativeConfig.toJson())
		}
	}

	/** 与广告加载、展示同在主线程应用配置，并保存到本地供下次启动使用。 */
	@MainThread
	internal fun applyConfig(config: JSONObject) {
		// 先完整解析，避免某一组无效时只切换了部分广告位。
		val nextOpen = config.optJSONObject("openConfig")?.toAdUnitConfig() ?: openConfig
		val nextInter = config.optJSONObject("interConfig")?.toAdUnitConfig() ?: interConfig
		val nextVideo = config.optJSONObject("videoConfig")?.toAdUnitConfig() ?: videoConfig
		val nextBanner = config.optJSONObject("bannerConfig")?.toBannerConfig() ?: bannerConfig
		val nextNative = config.optJSONObject("nativeConfig")?.toNativeConfig() ?: nativeConfig
		openConfig = nextOpen
		interConfig = nextInter
		videoConfig = nextVideo
		bannerConfig = nextBanner
		nativeConfig = nextNative
		AdmobLoader.onConfigChanged()
		saveAdmobConfig()
	}

	private fun JSONObject.toAdUnitConfig(): AdUnitConfig =
		AdUnitConfig(
			getString("id"),
			getLong("timeout"),
			getInt("poolSize"),
			getJSONObject("probeConfig").toProbeConfig()
		)

	private fun JSONObject.toProbeConfig(): ProbeConfig =
		ProbeConfig(
			ProbeMod.valueOf(getString("mod")),
			getLong("timeout"),
			getString("currency"),
			getJSONArray("instances").toProbeInstances()
		)

	private fun JSONArray.toProbeInstances(): List<ProbeInstance> =
		(0 until length()).map { index ->
			getJSONObject(index).let { instance ->
				ProbeInstance(
					instance.getString("instanceId"),
					instance.getString("label"),
					instance.getDouble("ecpm"),
					instance.getString("param")
				)
			}
		}

	private fun JSONObject.toBannerConfig(): BannerConfig =
		BannerConfig(
			getString("id")
		)

	private fun JSONObject.toNativeConfig(): NativeConfig =
		NativeConfig(
			getLong("timeout"),
			getJSONArray("ads").toNativeAds()
		)

	private fun JSONArray.toNativeAds(): List<NativeAdConfig> =
		(0 until length()).map { index ->
			getJSONObject(index).let { ad ->
				NativeAdConfig(
					ad.getString("HID"),
					ad.getString("MID"),
					ad.getString("LID")
				)
			}
		}

	private fun BannerConfig.toJson(): String =
		JSONObject().apply {
			put("id", id)
		}.toString()

	private fun NativeConfig.toJson(): String =
		JSONObject().apply {
			put("timeout", timeout)
			put("ads", JSONArray().apply {
				ads.forEach { ad ->
					put(JSONObject().apply {
						put("HID", ad.hID)
						put("MID", ad.mID)
						put("LID", ad.lID)
					})
				}
			})
		}.toString()

	private fun AdUnitConfig.toJson(): String =
		JSONObject().apply {
			put("id", id)
			put("timeout", timeout)
			put("poolSize", poolSize)
			put("probeConfig", JSONObject().apply {
				put("mod", probeConfig.mod.name)
				put("timeout", probeConfig.timeout)
				put("currency", probeConfig.currency)
				put("instances", JSONArray().apply {
					probeConfig.instances.forEach { instance ->
						put(JSONObject().apply {
							put("instanceId", instance.instanceId)
							put("label", instance.label)
							put("ecpm", instance.ecpm)
							put("param", instance.param)
						})
					}
				})
			})
		}.toString()
}

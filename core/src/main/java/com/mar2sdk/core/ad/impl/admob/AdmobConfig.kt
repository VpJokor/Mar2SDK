package com.mar2sdk.core.ad.impl.admob

import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

// AdMob 广告配置
object AdmobConfig {

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

	// mod：探价模式
	// timeout：探价超时时间，单位毫秒
	// currency：探价币种
	// instances：探价实例
	data class ProbeConfig(
		val mod: String,
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

	val testOpenID = "ca-app-pub-3940256099942544/9257395921"
	val testInterID = "ca-app-pub-3940256099942544/1033173712"
	val testVideoID = "ca-app-pub-3940256099942544/5224354917"

	var openConfig = AdUnitConfig("", 12600000L, 1, ProbeConfig("Reflect", 3000L, "USD", emptyList()))
	var interConfig = AdUnitConfig("", 3000000L, 1, ProbeConfig("Reflect", 3000L, "USD", emptyList()))
	var videoConfig = AdUnitConfig("", 3000000L, 1, ProbeConfig("Reflect", 3000L, "USD", emptyList()))

	private val isTest: Boolean
		get() = Core.appMod == AppMod.TEST || Core.appMod == AppMod.DEBUG

	val openID: String
		get() = if (isTest) testOpenID else openConfig.id
	val interID: String
		get() = if (isTest) testInterID else interConfig.id
	val videoID: String
		get() = if (isTest) testVideoID else videoConfig.id

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
		}
	}

	// 把配置保存到本地 (Preference)
	fun saveAdmobConfig() {
		with(AdmobKey) {
			PreferenceUtil.commitString(KEY_OPEN_CONFIG, openConfig.toJson())
			PreferenceUtil.commitString(KEY_INTER_CONFIG, interConfig.toJson())
			PreferenceUtil.commitString(KEY_VIDEO_CONFIG, videoConfig.toJson())
		}
	}

	/** 应用 Remote Config 的 JSON 配置，并保存到本地供下次启动使用。 */
	internal fun applyConfig(config: JSONObject) {
		with(config) {
			optJSONObject("openConfig")?.let { openConfig = it.toAdUnitConfig() }
			optJSONObject("interConfig")?.let { interConfig = it.toAdUnitConfig() }
			optJSONObject("videoConfig")?.let { videoConfig = it.toAdUnitConfig() }
		}
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
			getString("mod"),
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

	private fun AdUnitConfig.toJson(): String =
		JSONObject().apply {
			put("id", id)
			put("timeout", timeout)
			put("poolSize", poolSize)
			put("probeConfig", JSONObject().apply {
				put("mod", probeConfig.mod)
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

package com.mar2sdk.core.log

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

// 控制各日志渠道上报哪些事件。
object LogConfig {
	private const val DEFAULT_REPORT_BATCH_SIZE = 20
	private const val DEFAULT_REPORT_FLUSH_INTERVAL_MILLIS = 5_000L

	var fbEvents = listOf<String>()
	var localEvents = listOf<String>()
	var thEvents = listOf<String>()
	var netEvents = listOf<String>()

	/** 每批最多的事件条数，同时作为立即发送的条数阈值。 */
	@Volatile
	var reportBatchSize = DEFAULT_REPORT_BATCH_SIZE
		set(value) { if (value > 0) field = value }

	/** 从本轮首条事件开始等待的毫秒数；下一批次窗口读取最新配置。 */
	@Volatile
	var reportFlushIntervalMillis = DEFAULT_REPORT_FLUSH_INTERVAL_MILLIS
		set(value) { if (value > 0) field = value }

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 读取 SDK 打包资源中的默认上报规则。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.log_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			fbEvents = getJSONArray("fbEvents").toStringList()
			localEvents = getJSONArray("localEvents").toStringList()
			thEvents = getJSONArray("thEvents").toStringList()
			netEvents = getJSONArray("netEvents").toStringList()
			reportBatchSize = getInt("reportBatchSize").also { require(it > 0) { "reportBatchSize must be positive" } }
			reportFlushIntervalMillis = getLong("reportFlushIntervalMillis").also {
				require(it > 0) { "reportFlushIntervalMillis must be positive" }
			}
		}
	}

	// 读取已保存的上报规则，未保存的字段沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(LogKey) {
			fbEvents = readEvents(KEY_FB_EVENTS, fbEvents)
			localEvents = readEvents(KEY_LOCAL_EVENTS, localEvents)
			thEvents = readEvents(KEY_TH_EVENTS, thEvents)
			netEvents = readEvents(KEY_NET_EVENTS, netEvents)
			reportBatchSize = PreferenceUtil.getInt(KEY_REPORT_BATCH_SIZE, reportBatchSize)
			reportFlushIntervalMillis = PreferenceUtil.getLong(KEY_REPORT_FLUSH_INTERVAL_MILLIS, reportFlushIntervalMillis)
		}
	}

	// 将当前上报规则保存到本地 Preference。
	fun saveLogConfig() {
		with(LogKey) {
			PreferenceUtil.commitString(KEY_FB_EVENTS, fbEvents.toJson())
			PreferenceUtil.commitString(KEY_LOCAL_EVENTS, localEvents.toJson())
			PreferenceUtil.commitString(KEY_TH_EVENTS, thEvents.toJson())
			PreferenceUtil.commitString(KEY_NET_EVENTS, netEvents.toJson())
			PreferenceUtil.commitInt(KEY_REPORT_BATCH_SIZE, reportBatchSize)
			PreferenceUtil.commitLong(KEY_REPORT_FLUSH_INTERVAL_MILLIS, reportFlushIntervalMillis)
		}
	}

	internal fun applyConfig(config: JSONObject) {
		config.optJSONArray("fbEvents")?.let { fbEvents = it.toStringList() }
		config.optJSONArray("localEvents")?.let { localEvents = it.toStringList() }
		config.optJSONArray("thEvents")?.let { thEvents = it.toStringList() }
		config.optJSONArray("netEvents")?.let { netEvents = it.toStringList() }
		reportBatchSize = config.positiveInt("reportBatchSize", reportBatchSize)
		reportFlushIntervalMillis = config.positiveLong("reportFlushIntervalMillis", reportFlushIntervalMillis)
		saveLogConfig()
	}

	// 判断事件是否启用；`*` 表示启用全部事件。
	fun isEnabled(events: Collection<String>, eventName: String): Boolean =
		"*" in events || eventName in events

	private fun readEvents(key: String, defaultValue: List<String>): List<String> =
		JSONArray(PreferenceUtil.getString(key, defaultValue.toJson())).toStringList()

	private fun JSONArray.toStringList(): List<String> =
		(0 until length()).map { index -> getString(index) }

	private fun List<String>.toJson(): String = JSONArray(this).toString()

	private fun JSONObject.positiveInt(key: String, fallback: Int): Int =
		opt(key)?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback

	private fun JSONObject.positiveLong(key: String, fallback: Long): Long =
		opt(key)?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: fallback
}

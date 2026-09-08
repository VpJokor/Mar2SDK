package com.mar2sdk.core.notify

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

object NotificationConfig {
	private const val DEFAULT_CHANNEL_COUNT = 3
	private const val DEFAULT_FIRST_DELAY = 300
	private const val DEFAULT_INTERVAL_SECOND = 60
	private const val DEFAULT_24H_MAX = 999
	private const val DEFAULT_1H_MAX = 5


	// APP通知的通道数
	var ChannelCount = DEFAULT_CHANNEL_COUNT
	// 总开关
	var isSend = false
	// APP在前台是否发送通知
	var isForgroundSend = false
	// 熄屏是否发送通知
	var isScreenOffSend = false
	// 锁屏是否发送通知
	var isScreenLockSend = false
	// 全局首次发送通知的延迟(相对打开APP)，单位秒
	var firstDelay = DEFAULT_FIRST_DELAY
	// 通知发送间隔，单位秒
	var intervalSecond = DEFAULT_INTERVAL_SECOND
	// 24小时内最多发送的通知数
	var max24H = DEFAULT_24H_MAX
	// 1小时内最多发送的通知数
	var max1H = DEFAULT_1H_MAX
	// 各触发场景的通知配置
	var triggers = mapOf<String, NotificationTrigger>()
	// 定时通知配置
	var timer = listOf<NotificationTimer>()
	// 通知内容
	var contents = listOf<NotificationContent>()


	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	// 从打包资源读取默认配置。
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.notification_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		resetToDefaults()
		applyConfig(config)
	}

	// 从本地读取配置，未保存的配置项沿用打包资源中的值。
	fun loadConfigFromPreference() {
		with(NotificationKey) {
			ChannelCount = PreferenceUtil.getInt(KEY_CHANNEL_COUNT, ChannelCount)
			isSend = PreferenceUtil.getBoolean(KEY_IS_SEND, isSend)
			isForgroundSend = PreferenceUtil.getBoolean(KEY_IS_FORGROUND_SEND, isForgroundSend)
			isScreenOffSend = PreferenceUtil.getBoolean(KEY_IS_SCREEN_OFF_SEND, isScreenOffSend)
			isScreenLockSend = PreferenceUtil.getBoolean(KEY_IS_SCREEN_LOCK_SEND, isScreenLockSend)
			firstDelay = PreferenceUtil.getInt(KEY_FIRST_DELAY, firstDelay)
			intervalSecond = PreferenceUtil.getInt(KEY_INTERVAL_SECOND, intervalSecond)
			max24H = PreferenceUtil.getInt(KEY_24H_MAX, max24H)
			max1H = PreferenceUtil.getInt(KEY_1H_MAX, max1H)
			triggers = JSONObject(
				PreferenceUtil.getString(KEY_TRIGGERS, triggers.toTriggersJson())
			).toTriggers()
			timer = JSONArray(
				PreferenceUtil.getString(KEY_TIMER, timer.toTimerJson())
			).toTimer()
			contents = JSONArray(
				PreferenceUtil.getString(KEY_CONTENTS, contents.toContentsJson())
			).toContents()
		}
	}

	// 把配置保存到本地 (Preference)。
	fun saveNotificationConfig() {
		with(NotificationKey) {
			PreferenceUtil.commitInt(KEY_CHANNEL_COUNT, ChannelCount)
			PreferenceUtil.commitBoolean(KEY_IS_SEND, isSend)
			PreferenceUtil.commitBoolean(KEY_IS_FORGROUND_SEND, isForgroundSend)
			PreferenceUtil.commitBoolean(KEY_IS_SCREEN_OFF_SEND, isScreenOffSend)
			PreferenceUtil.commitBoolean(KEY_IS_SCREEN_LOCK_SEND, isScreenLockSend)
			PreferenceUtil.commitInt(KEY_FIRST_DELAY, firstDelay)
			PreferenceUtil.commitInt(KEY_INTERVAL_SECOND, intervalSecond)
			PreferenceUtil.commitInt(KEY_24H_MAX, max24H)
			PreferenceUtil.commitInt(KEY_1H_MAX, max1H)
			PreferenceUtil.commitString(KEY_TRIGGERS, triggers.toTriggersJson())
			PreferenceUtil.commitString(KEY_TIMER, timer.toTimerJson())
			PreferenceUtil.commitString(KEY_CONTENTS, contents.toContentsJson())
		}
	}

	/** Apply values present in a JSON object and retain defaults for missing values. */
	internal fun applyConfig(config: JSONObject) {
		ChannelCount = config.optInt("ChannelCount", config.optInt("channelCount", ChannelCount))
		isSend = config.optBoolean("isSend", isSend)
		isForgroundSend = config.optBoolean(
			"isForgroundSend",
			config.optBoolean("isForegroundSend", isForgroundSend)
		)
		isScreenOffSend = config.optBoolean("isScreenOffSend", isScreenOffSend)
		isScreenLockSend = config.optBoolean("isScreenLockSend", isScreenLockSend)
		firstDelay = config.optInt("first_delay", firstDelay)
		intervalSecond = config.optInt("interval_second", intervalSecond)
		max24H = config.optInt("24HMax", max24H)
		max1H = config.optInt("1HMax", max1H)
		config.optJSONObject("triggers")?.let { triggers = it.toTriggers() }
		config.optJSONArray("timer")?.let { timer = it.toTimer() }
		config.optJSONArray("contents")?.let { contents = it.toContents() }
	}

	private fun resetToDefaults() {
		ChannelCount = DEFAULT_CHANNEL_COUNT
		isSend = false
		isForgroundSend = false
		isScreenOffSend = false
		isScreenLockSend = false
		firstDelay = DEFAULT_FIRST_DELAY
		intervalSecond = DEFAULT_INTERVAL_SECOND
		max24H = DEFAULT_24H_MAX
		max1H = DEFAULT_1H_MAX
		triggers = emptyMap()
		timer = emptyList()
		contents = emptyList()
	}

	private fun JSONObject.toTriggers(): Map<String, NotificationTrigger> =
		keys().asSequence().associateWith { name ->
			with(getJSONObject(name)) {
				NotificationTrigger(
					delay = optInt("delay", 0),
					count = optInt("count", 0),
					interval = optInt("interval", 0)
				)
			}
		}

	private fun Map<String, NotificationTrigger>.toTriggersJson(): String =
		JSONObject().apply {
			forEach { (name, trigger) ->
				put(name, JSONObject().apply {
					put("delay", trigger.delay)
					put("count", trigger.count)
					put("interval", trigger.interval)
				})
			}
		}.toString()

	private fun JSONArray.toTimer(): List<NotificationTimer> =
		(0 until length()).map { index ->
			with(getJSONObject(index)) {
				NotificationTimer(
					name = optString("name", ""),
					HH = optInt("HH", 0),
					MM = optInt("MM", 0),
					count = optInt("count", 0)
				)
			}
		}

	private fun List<NotificationTimer>.toTimerJson(): String =
		JSONArray(map { item ->
			JSONObject().apply {
				put("name", item.name)
				put("HH", item.HH)
				put("MM", item.MM)
				put("count", item.count)
			}
		}).toString()

	private fun JSONArray.toContents(): List<NotificationContent> =
		(0 until length()).map { index ->
			with(getJSONObject(index)) {
				val scenes = optJSONArray("Scenes") ?: JSONArray()
				val languages = optJSONObject("Languages") ?: JSONObject()
				NotificationContent(
					Title = optString("Title", ""),
					Content = optString("Content", ""),
					Button = optString("Button", ""),
					Scenes = (0 until scenes.length()).map { scenes.getString(it) },
					Route = optString("Route", ""),
					Languages = languages.keys().asSequence().associateWith { language ->
						with(languages.getJSONObject(language)) {
							NotificationLanguage(
								title = optString("title", ""),
								content = optString("content", ""),
								button = optString("button", "")
							)
						}
					}
				)
			}
		}

	private fun List<NotificationContent>.toContentsJson(): String =
		JSONArray(map { item ->
			JSONObject().apply {
				put("Title", item.Title)
				put("Content", item.Content)
				put("Button", item.Button)
				put("Scenes", JSONArray(item.Scenes))
				put("Route", item.Route)
				put("Languages", JSONObject().apply {
					item.Languages.forEach { (language, content) ->
						put(language, JSONObject().apply {
							put("title", content.title)
							put("content", content.content)
							put("button", content.button)
						})
					}
				})
			}
		}).toString()
}

// 触发场景的通知配置，delay和interval单位为秒
data class NotificationTrigger(
	val delay: Int = 0,
	val count: Int = 0,
	val interval: Int = 0
)

// 定时通知配置，HH为小时，MM为分钟
data class NotificationTimer(
	val name: String = "",
	val HH: Int = 0,
	val MM: Int = 0,
	val count: Int = 0
)

// 通知内容及适用场景
data class NotificationContent(
	val Title: String = "",
	val Content: String = "",
	val Button: String = "",
	val Scenes: List<String> = emptyList(),
	val Route: String = "",
	val Languages: Map<String, NotificationLanguage> = emptyMap()
)

// 指定语言的通知文案
data class NotificationLanguage(
	val title: String = "",
	val content: String = "",
	val button: String = ""
)

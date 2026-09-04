package com.mar2sdk.core.notify

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

object NotificationConfig {
	private const val DEFAULT_CHANNEL_COUNT = 3
	private val DEFAULT_CONTENTS = listOf(
		NotificationContent(listOf(), "Title", "Content", "Button", "", "Route")
	)

	// APP通知的通道数
	var ChannelCount = DEFAULT_CHANNEL_COUNT
	// APP在前台是否发送通知
	var isForgroundSend = false
	// 熄屏是否发送通知
	var isScreenOffSend = false
	// 锁屏是否发送通知
	var isScreenLockSend = false

	// 通知文案
	var contents = DEFAULT_CONTENTS

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
			isForgroundSend = PreferenceUtil.getBoolean(KEY_IS_FORGROUND_SEND, isForgroundSend)
			isScreenOffSend = PreferenceUtil.getBoolean(KEY_IS_SCREEN_OFF_SEND, isScreenOffSend)
			isScreenLockSend = PreferenceUtil.getBoolean(KEY_IS_SCREEN_LOCK_SEND, isScreenLockSend)

			val contentsJson = PreferenceUtil.getString(KEY_CONTENTS, "")
			if (contentsJson.isNotBlank()) {
				contents = runCatching { parseNotificationContents(contentsJson) }
					.getOrDefault(contents)
			}
		}
	}

	fun saveNotificationConfig() {
		with(NotificationKey) {
			PreferenceUtil.commitInt(KEY_CHANNEL_COUNT, ChannelCount)
			PreferenceUtil.commitBoolean(KEY_IS_FORGROUND_SEND, isForgroundSend)
			PreferenceUtil.commitBoolean(KEY_IS_SCREEN_OFF_SEND, isScreenOffSend)
			PreferenceUtil.commitBoolean(KEY_IS_SCREEN_LOCK_SEND, isScreenLockSend)
			PreferenceUtil.commitString(KEY_CONTENTS, serializeNotificationContents(contents))
		}
	}

	/** Apply values present in a JSON object and retain defaults for missing values. */
	internal fun applyConfig(config: JSONObject) {
		ChannelCount = config.optInt("ChannelCount", config.optInt("channelCount", ChannelCount))
		isForgroundSend = config.optBoolean(
			"isForgroundSend",
			config.optBoolean("isForegroundSend", isForgroundSend)
		)
		isScreenOffSend = config.optBoolean("isScreenOffSend", isScreenOffSend)
		isScreenLockSend = config.optBoolean("isScreenLockSend", isScreenLockSend)

		val contentsValue = config.opt("contents") ?: config.opt("notification_content")
		val contentsJson = when (contentsValue) {
			is JSONArray -> contentsValue.toString()
			is String -> contentsValue
			else -> null
		}
		if (!contentsJson.isNullOrBlank()) {
			contents = runCatching { parseNotificationContents(contentsJson) }
				.getOrDefault(contents)
		}
	}

	private fun resetToDefaults() {
		ChannelCount = DEFAULT_CHANNEL_COUNT
		isForgroundSend = false
		isScreenOffSend = false
		isScreenLockSend = false
		contents = DEFAULT_CONTENTS
	}

}

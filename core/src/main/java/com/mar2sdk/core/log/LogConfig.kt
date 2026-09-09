package com.mar2sdk.core.log

import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject

/** Controls which events are sent to each logging backend. */
object LogConfig {
	var fbEvents = listOf<String>()
	var localEvents = listOf<String>()
	var thEvents = listOf<String>()
	var netEvents = listOf<String>()

	fun init() {
		loadConfigFromRaw()
		loadConfigFromPreference()
	}

	/** Loads the default routing rules packaged with the SDK. */
	fun loadConfigFromRaw() {
		val config = Core.app.resources.openRawResource(R.raw.log_config)
			.bufferedReader()
			.use { JSONObject(it.readText()) }

		with(config) {
			fbEvents = getJSONArray("fbEvents").toStringList()
			localEvents = getJSONArray("localEvents").toStringList()
			thEvents = getJSONArray("thEvents").toStringList()
			netEvents = getJSONArray("netEvents").toStringList()
		}
	}

	/** Loads saved routing rules, retaining packaged values for missing keys. */
	fun loadConfigFromPreference() {
		with(LogKey) {
			fbEvents = readEvents(KEY_FB_EVENTS, fbEvents)
			localEvents = readEvents(KEY_LOCAL_EVENTS, localEvents)
			thEvents = readEvents(KEY_TH_EVENTS, thEvents)
			netEvents = readEvents(KEY_NET_EVENTS, netEvents)
		}
	}

	/** Saves the current routing rules to local preferences. */
	fun saveLogConfig() {
		with(LogKey) {
			PreferenceUtil.commitString(KEY_FB_EVENTS, fbEvents.toJson())
			PreferenceUtil.commitString(KEY_LOCAL_EVENTS, localEvents.toJson())
			PreferenceUtil.commitString(KEY_TH_EVENTS, thEvents.toJson())
			PreferenceUtil.commitString(KEY_NET_EVENTS, netEvents.toJson())
		}
	}

	/** Returns whether an event is enabled; `*` enables every event. */
	fun isEnabled(events: Collection<String>, eventName: String): Boolean =
		"*" in events || eventName in events

	private fun readEvents(key: String, defaultValue: List<String>): List<String> =
		JSONArray(PreferenceUtil.getString(key, defaultValue.toJson())).toStringList()

	private fun JSONArray.toStringList(): List<String> =
		(0 until length()).map { index -> getString(index) }

	private fun List<String>.toJson(): String = JSONArray(this).toString()
}

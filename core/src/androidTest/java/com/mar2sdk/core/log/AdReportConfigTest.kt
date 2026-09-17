package com.mar2sdk.core.log

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdReportConfigTest {

	private val restore = mutableListOf<() -> Unit>()
	private val defaultEvents = listOf("ad_revenue", "ad_impression", "ad_click")

	@Before
	fun setUp() {
		val appField = Core::class.java.getDeclaredField("app").apply { isAccessible = true }
		val previousApp = appField.get(null)
		restore += { appField.set(null, previousApp) }
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		Core.app = context.applicationContext as Application

		val preferencesField = PreferenceUtil::class.java.getDeclaredField("sharedPreferences")
			.apply { isAccessible = true }
		val previousPreferences = preferencesField.get(null)
		restore += { preferencesField.set(null, previousPreferences) }
		PreferenceUtil.resetForTests()
		PreferenceUtil.init()

		val preferences = context.getSharedPreferences("preference", Context.MODE_PRIVATE)
		val eventKeys = listOf(LogKey.KEY_FB_EVENTS, LogKey.KEY_LOCAL_EVENTS, LogKey.KEY_TH_EVENTS, LogKey.KEY_NET_EVENTS)
		val savedEvents = eventKeys.associateWith { preferences.getString(it, null) }
		val hadBatchSize = preferences.contains(LogKey.KEY_REPORT_BATCH_SIZE)
		val savedBatchSize = preferences.getInt(LogKey.KEY_REPORT_BATCH_SIZE, 20)
		val hadFlushInterval = preferences.contains(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS)
		val savedFlushInterval = preferences.getLong(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS, 5_000L)
		restore += {
			preferences.edit().apply {
				savedEvents.forEach { (key, value) -> putString(key, value) }
				if (hadBatchSize) putInt(LogKey.KEY_REPORT_BATCH_SIZE, savedBatchSize)
				else remove(LogKey.KEY_REPORT_BATCH_SIZE)
				if (hadFlushInterval) putLong(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS, savedFlushInterval)
				else remove(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS)
			}.commit()
			Unit
		}
		for (property in listOf(LogConfig::fbEvents, LogConfig::localEvents, LogConfig::thEvents, LogConfig::netEvents)) {
			val previous = property.get()
			restore += { property.set(previous) }
		}
		val previousBatchSize = LogConfig.reportBatchSize
		val previousFlushInterval = LogConfig.reportFlushIntervalMillis
		restore += {
			LogConfig.reportBatchSize = previousBatchSize
			LogConfig.reportFlushIntervalMillis = previousFlushInterval
		}
		(eventKeys + listOf(LogKey.KEY_REPORT_BATCH_SIZE, LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS))
			.forEach(PreferenceUtil::removeByKey)
	}

	@After
	fun tearDown() {
		restore.asReversed().forEach { it() }
		restore.clear()
	}

	@Test
	fun packagedDefaultsEnableAllThreeReportEvents() {
		loadConfig()

		assertEquals(defaultEvents, LogConfig.netEvents)
		assertEquals(20, LogConfig.reportBatchSize)
		assertEquals(5_000L, LogConfig.reportFlushIntervalMillis)
		defaultEvents.forEach { assertTrue(LogConfig.isEnabled(LogConfig.netEvents, it)) }
		assertFalse(LogConfig.isEnabled(LogConfig.netEvents, "app_start"))
	}

	@Test
	fun preservesExplicitlyDisabledReporting() {
		PreferenceUtil.commitString(LogKey.KEY_NET_EVENTS, "[]")

		loadConfig()

		assertTrue(LogConfig.netEvents.isEmpty())
		assertEquals("[]", PreferenceUtil.getString(LogKey.KEY_NET_EVENTS, ""))
		defaultEvents.forEach { assertFalse(LogConfig.isEnabled(LogConfig.netEvents, it)) }
	}

	@Test
	fun preservesCustomAndWildcardRules() {
		for (events in listOf(listOf("ad_click", "custom_event"), listOf("eventA", "eventB", "eventC"), listOf("*"))) {
			val stored = JSONArray(events).toString()
			PreferenceUtil.commitString(LogKey.KEY_NET_EVENTS, stored)

			loadConfig()

			assertEquals(events, LogConfig.netEvents)
			assertEquals(stored, PreferenceUtil.getString(LogKey.KEY_NET_EVENTS, ""))
		}
		defaultEvents.forEach { assertTrue(LogConfig.isEnabled(LogConfig.netEvents, it)) }
	}

	@Test
	fun missingPreferencesPreserveLoadedBatchSettings() {
		LogConfig.loadConfigFromRaw()
		LogConfig.reportBatchSize = 7
		LogConfig.reportFlushIntervalMillis = 1_250L

		LogConfig.loadConfigFromPreference()

		assertEquals(7, LogConfig.reportBatchSize)
		assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
	}

	@Test
	fun persistsBatchSettingsAndRestoresThemOverPackagedDefaults() {
		loadConfig()
		LogConfig.reportBatchSize = 7
		LogConfig.reportFlushIntervalMillis = 4_000_000_000L
		LogConfig.saveLogConfig()

		assertEquals(7, PreferenceUtil.getInt(LogKey.KEY_REPORT_BATCH_SIZE, -1))
		assertEquals(4_000_000_000L, PreferenceUtil.getLong(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS, -1L))
		LogConfig.loadConfigFromRaw()
		assertEquals(20, LogConfig.reportBatchSize)
		assertEquals(5_000L, LogConfig.reportFlushIntervalMillis)
		LogConfig.loadConfigFromPreference()
		assertEquals(7, LogConfig.reportBatchSize)
		assertEquals(4_000_000_000L, LogConfig.reportFlushIntervalMillis)
	}

	@Test
	fun partialJsonUpdatesOnlyProvidedBatchSettingsAndPersistsThem() {
		loadConfig()
		LogConfig.reportBatchSize = 7
		LogConfig.reportFlushIntervalMillis = 1_250L
		LogConfig.netEvents = listOf("ad_click")

		LogConfig.applyConfig(JSONObject().put("reportBatchSize", 12))
		assertEquals(12, LogConfig.reportBatchSize)
		assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
		LogConfig.applyConfig(JSONObject().put("reportFlushIntervalMillis", 2_500L))
		LogConfig.applyConfig(JSONObject())
		assertEquals(12, LogConfig.reportBatchSize)
		assertEquals(2_500L, LogConfig.reportFlushIntervalMillis)
		assertEquals(listOf("ad_click"), LogConfig.netEvents)
		loadConfig()
		assertEquals(12, LogConfig.reportBatchSize)
		assertEquals(2_500L, LogConfig.reportFlushIntervalMillis)
		assertEquals(listOf("ad_click"), LogConfig.netEvents)
	}

	@Test
	fun invalidJsonBatchValuesKeepExistingSettings() {
		loadConfig()
		LogConfig.reportBatchSize = 7
		LogConfig.reportFlushIntervalMillis = 1_250L
		for (invalid in listOf(0, -1, 1.5, "invalid", true, JSONObject.NULL, JSONObject(), JSONArray())) {
			LogConfig.applyConfig(JSONObject().put("reportBatchSize", invalid).put("reportFlushIntervalMillis", invalid))
			assertEquals(7, LogConfig.reportBatchSize)
			assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
		}
		LogConfig.applyConfig(JSONObject("""{"reportBatchSize":2147483648,"reportFlushIntervalMillis":9223372036854775808}"""))

		assertEquals(7, LogConfig.reportBatchSize)
		assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
		loadConfig()
		assertEquals(7, LogConfig.reportBatchSize)
		assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
	}

	@Test
	fun invalidStoredOrDirectBatchValuesKeepExistingSettings() {
		loadConfig()
		LogConfig.reportBatchSize = 7
		LogConfig.reportFlushIntervalMillis = 1_250L
		for (invalid in listOf(0, -1)) {
			LogConfig.reportBatchSize = invalid
			LogConfig.reportFlushIntervalMillis = invalid.toLong()
			assertEquals(7, LogConfig.reportBatchSize)
			assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)

			PreferenceUtil.commitInt(LogKey.KEY_REPORT_BATCH_SIZE, invalid)
			PreferenceUtil.commitLong(LogKey.KEY_REPORT_FLUSH_INTERVAL_MILLIS, invalid.toLong())
			LogConfig.loadConfigFromPreference()
			assertEquals(7, LogConfig.reportBatchSize)
			assertEquals(1_250L, LogConfig.reportFlushIntervalMillis)
		}
	}

	private fun loadConfig() {
		LogConfig.loadConfigFromRaw()
		LogConfig.loadConfigFromPreference()
	}
}

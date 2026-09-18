package com.mar2sdk.core.ad.impl.admob

import android.content.SharedPreferences
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.PreferenceUtil
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AdmobConfigTest {

	private val restore = mutableListOf<() -> Unit>()

	@Before
	fun setUp() {
		preserveFields(Core::class.java, setOf("appMod"))
		Core.appMod = AppMod.RELEASE
		preserveFields(PreferenceUtil::class.java, setOf("sharedPreferences"))
		PreferenceUtil.resetForTests()
		preserveFields(AdmobConfig::class.java)
	}

	@After
	fun tearDown() {
		restore.asReversed().forEach { it() }
	}

	@Test
	fun readsAllFieldsFromBundledNestedConfig() {
		val resource = listOf(
			File("src/main/res/raw/admob_config.json"),
			File("core/src/main/res/raw/admob_config.json"),
		).first { it.isFile }
		val config = JSONObject(resource.readText())

		AdmobConfig.applyConfig(config)

		assertMatches(config)
	}

	@Test
	fun keepsEachFormatsIdsTimeoutsPoolsAndProbeFieldsIndependent() {
		val config = distinctConfig()

		AdmobConfig.applyConfig(config)

		assertMatches(config)
	}

	@Test
	fun partialUpdatesReplaceProvidedGroupsAndPreserveOmittedGroups() {
		val config = distinctConfig()
		AdmobConfig.applyConfig(config)
		val replacement = JSONObject("""
			{"id":"updated-open","timeout":9876,"poolSize":7,
			 "probeConfig":{"mod":"updated-mode","timeout":654,"currency":"CNY","instances":[]}}
		""")

		AdmobConfig.applyConfig(JSONObject().put("openConfig", replacement))

		config.put("openConfig", replacement)
		assertMatches(config)

		AdmobConfig.applyConfig(JSONObject())
		assertMatches(config)
	}

	@Test
	fun usesIdsForTheCurrentAppMode() {
		AdmobConfig.applyConfig(distinctConfig())

		for (mode in AppMod.entries) {
			Core.appMod = mode
			val expected = if (mode == AppMod.DEBUG || mode == AppMod.TEST) {
				listOf(AdmobConfig.testOpenID, AdmobConfig.testInterID, AdmobConfig.testVideoID)
			} else {
				listOf("open-id", "inter-id", "video-id")
			}
			assertEquals(mode.name, expected, activeIds())
		}
		assertEquals(listOf("open-id", "inter-id", "video-id"), formatConfigs().map { it.id })
	}

	@Test
	fun ignoresLegacyJsonFields() {
		val config = distinctConfig()
		AdmobConfig.applyConfig(config)

		AdmobConfig.applyConfig(JSONObject("""
			{"releaseOpenID":"legacy-open","releaseInterID":"legacy-inter","releaseVideoID":"legacy-video",
			 "openTimeout":1200.5,"interTimeout":2300,"videoTimeout":3400,
			 "openPoolSize":5,"interPoolSize":6,"videoPoolSize":7}
		"""))

		assertMatches(config)
	}

	@Test(expected = JSONException::class)
	fun rejectsIncompleteProvidedGroups() {
		AdmobConfig.applyConfig(distinctConfig())

		AdmobConfig.applyConfig(JSONObject("""{"openConfig":{"probeConfig":{"timeout":9876}}}"""))
	}

	@Test
	fun persistsAndReloadsAllFormatAndProbeFields() {
		val field = PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
		field.set(null, inMemoryPreferences())
		val resetConfig = snapshotFields(AdmobConfig::class.java)
		val config = distinctConfig()

		AdmobConfig.applyConfig(config)
		resetConfig()
		AdmobConfig.loadConfigFromPreference()

		assertMatches(config)
	}

	@Test
	fun missingStoredGroupsKeepCurrentValuesAndIgnoreLegacyPreferences() {
		val config = distinctConfig()
		AdmobConfig.applyConfig(config)
		val preferences = inMemoryPreferences()
		preferences.edit()
			.putString("mar2sdk.admob_config.releaseOpenID", "legacy-open")
			.putString("mar2sdk.admob_config.openTimeout", "1234.5")
			.putInt("mar2sdk.admob_config.openPoolSize", 7)
			.putString("mar2sdk.admob_config.openProbeConfig", """{"mod":"legacy"}""")
			.commit()
		val field = PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
		field.set(null, preferences)

		AdmobConfig.loadConfigFromPreference()

		assertMatches(config)
	}

	private fun distinctConfig(): JSONObject = JSONObject().apply {
		listOf("open", "inter", "video").forEachIndexed { index, format ->
			put("${format}Config", JSONObject().apply {
				put("id", "$format-id")
				put("timeout", (index + 1) * 1000L)
				put("poolSize", index + 2)
				put("probeConfig", JSONObject().apply {
					put("mod", "$format-mode")
					put("timeout", (index + 1) * 100L)
					put("currency", listOf("USD", "EUR", "JPY")[index])
					put("instances", JSONArray().put(JSONObject().apply {
						put("instanceId", "$format-instance")
						put("label", "$format-label")
						put("ecpm", index + 0.25)
						put("param", "{\"format\":\"$format\"}")
					}))
				})
			})
		}
	}

	private fun assertMatches(config: JSONObject) {
		val formats = formatConfigs()
		listOf("open", "inter", "video").forEachIndexed { index, format ->
			val expected = config.getJSONObject("${format}Config")
			assertEquals(format, expected.getString("id"), formats[index].id)
			assertEquals(format, expected.getLong("timeout"), formats[index].timeout)
			assertEquals(format, expected.getInt("poolSize"), formats[index].poolSize)
			val probe = expected.getJSONObject("probeConfig")
			val instances = probe.getJSONArray("instances")
			assertEquals(format, AdmobConfig.ProbeConfig(
				mod = probe.getString("mod"),
				timeout = probe.getLong("timeout"),
				currency = probe.getString("currency"),
				instances = List(instances.length()) { instanceIndex ->
					val instance = instances.getJSONObject(instanceIndex)
					AdmobConfig.ProbeInstance(instance.getString("instanceId"), instance.getString("label"),
						instance.getDouble("ecpm"), instance.getString("param"))
				},
			), formats[index].probeConfig)
		}
		assertEquals(formats.map { it.id }, activeIds())
	}

	private fun formatConfigs() = listOf(AdmobConfig.openConfig, AdmobConfig.interConfig, AdmobConfig.videoConfig)
	private fun activeIds() = listOf(AdmobConfig.openID, AdmobConfig.interID, AdmobConfig.videoID)

	private fun preserveFields(type: Class<*>, names: Set<String>? = null) {
		restore += snapshotFields(type, names)
	}

	private fun snapshotFields(type: Class<*>, names: Set<String>? = null): () -> Unit {
		val values = type.declaredFields
			.filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && (names == null || it.name in names) }
			.associateWith { it.isAccessible = true; it.get(null) }
		return { values.forEach { (field, value) -> field.set(null, value) } }
	}

	private fun inMemoryPreferences(): SharedPreferences {
		val values = mutableMapOf<String, Any?>()
		val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
			arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
			when (method.name) {
				"putString", "putInt", "putLong" -> { values[args!![0] as String] = args[1]; proxy }
				"commit" -> true
				else -> error("Unexpected editor call: ${method.name}")
			}
		} as SharedPreferences.Editor
		return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
			arrayOf(SharedPreferences::class.java)) { _, method, args ->
			when (method.name) {
				"edit" -> editor
				"getString", "getInt", "getLong" -> values[args!![0] as String] ?: args[1]
				else -> error("Unexpected preferences call: ${method.name}")
			}
		} as SharedPreferences
	}
}

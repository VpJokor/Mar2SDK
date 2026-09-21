package com.mar2sdk.core.ad.impl.admob

import android.content.SharedPreferences
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.impl.admob.ProbeMod
import com.mar2sdk.core.common.PreferenceUtil
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
			 "probeConfig":{"mod":"ADAPTER_L","timeout":654,"currency":"CNY","instances":[]}}
		""")

		AdmobConfig.applyConfig(JSONObject().put("openConfig", replacement))

		config.put("openConfig", replacement)
		assertMatches(config)

		val bannerReplacement = JSONObject().put("id", "updated-banner")
		AdmobConfig.applyConfig(JSONObject().put("bannerConfig", bannerReplacement))

		config.put("bannerConfig", bannerReplacement)
		assertMatches(config)

		AdmobConfig.applyConfig(JSONObject())
		assertMatches(config)
	}

	@Test
	fun nativeOnlyUpdatesReplaceAdsAndAcceptEmptyLists() {
		val config = distinctConfig()
		AdmobConfig.applyConfig(config)
		val replacements = listOf(
			JSONObject("""{"timeout":54321,"ads":[{"HID":"updated-high","MID":"updated-mid","LID":"updated-low"}]}"""),
			JSONObject("""{"timeout":12345,"ads":[]}"""),
		)

		for (replacement in replacements) {
			AdmobConfig.applyConfig(JSONObject().put("nativeConfig", replacement))

			config.put("nativeConfig", replacement)
			assertMatches(config)
		}
	}

	@Test
	fun usesIdsForTheCurrentAppMode() {
		AdmobConfig.applyConfig(distinctConfig())

		for (mode in AppMod.entries) {
			Core.appMod = mode
			val expected = if (mode == AppMod.DEBUG || mode == AppMod.TEST) {
				listOf(AdmobConfig.testOpenID, AdmobConfig.testInterID,
					AdmobConfig.testVideoID, AdmobConfig.testBannerID)
			} else {
				listOf("open-id", "inter-id", "video-id", "banner-id")
			}
			assertEquals(mode.name, expected, activeIds())
		}
		assertEquals(listOf("open-id", "inter-id", "video-id"), formatConfigs().map { it.id })
		assertEquals("banner-id", AdmobConfig.bannerConfig.id)
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
	fun invalidLaterGroupPreservesAllCurrentAndPersistedConfig() {
		val preferences = inMemoryPreferences()
		PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply {
			isAccessible = true
			set(null, preferences)
		}
		val original = distinctConfig()
		AdmobConfig.applyConfig(original)
		val originalConfigs = formatConfigs()
		val keys = listOf(AdmobKey.KEY_OPEN_CONFIG, AdmobKey.KEY_INTER_CONFIG,
			AdmobKey.KEY_VIDEO_CONFIG, AdmobKey.KEY_NATIVE_CONFIG, AdmobKey.KEY_BANNER_CONFIG)
		val originalStored = keys.associateWith { preferences.getString(it, null) }
		for (invalidGroup in listOf("interConfig", "nativeConfig", "bannerConfig")) {
			val invalidUpdate = distinctConfig().apply {
				getJSONObject("openConfig").put("id", "replacement-open")
				getJSONObject("videoConfig").put("id", "replacement-video")
				getJSONObject("nativeConfig").put("timeout", 98765L)
				getJSONObject("bannerConfig").put("id", "replacement-banner")
				when (invalidGroup) {
					"interConfig" -> getJSONObject(invalidGroup).remove("probeConfig")
					"nativeConfig" -> getJSONObject(invalidGroup).getJSONArray("ads").getJSONObject(1).remove("MID")
					"bannerConfig" -> getJSONObject(invalidGroup).remove("id")
				}
			}

			assertThrows(JSONException::class.java) { AdmobConfig.applyConfig(invalidUpdate) }

			assertEquals(originalConfigs, formatConfigs())
			assertMatches(original)
			assertEquals(originalStored, keys.associateWith { preferences.getString(it, null) })
		}
	}

	@Test
	fun persistsAndReloadsAllProbeModesAndFormatFields() {
		val field = PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply { isAccessible = true }
		val preferences = inMemoryPreferences()
		field.set(null, preferences)
		val resetConfig = snapshotFields(AdmobConfig::class.java)

		for (mode in ProbeMod.entries) {
			val config = distinctConfig().apply {
				listOf("open", "inter", "video").forEach { format ->
					getJSONObject("${format}Config").getJSONObject("probeConfig").put("mod", mode.name)
				}
			}

			AdmobConfig.applyConfig(config)
			assertEquals(List(3) { mode }, formatConfigs().map { it.probeConfig.mod })
			listOf(AdmobKey.KEY_OPEN_CONFIG, AdmobKey.KEY_INTER_CONFIG, AdmobKey.KEY_VIDEO_CONFIG).forEach { key ->
				val stored = JSONObject(preferences.getString(key, null)!!)
				assertEquals(mode.name, stored.getJSONObject("probeConfig").getString("mod"))
			}
			resetConfig()
			AdmobConfig.loadConfigFromPreference()

			assertMatches(config)
		}
	}

	@Test(expected = IllegalArgumentException::class)
	fun rejectsUnknownProbeModes() {
		val config = distinctConfig()
		config.getJSONObject("openConfig").getJSONObject("probeConfig").put("mod", "UNKNOWN")

		AdmobConfig.applyConfig(config)
	}

	@Test(expected = IllegalArgumentException::class)
	fun rejectsLegacyProbeModeNames() {
		val config = distinctConfig()
		config.getJSONObject("openConfig").getJSONObject("probeConfig").put("mod", "Reflect")

		AdmobConfig.applyConfig(config)
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
					put("mod", ProbeMod.entries[index].name)
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
		put("nativeConfig", JSONObject().apply {
			put("timeout", 4000L)
			put("ads", JSONArray().apply {
				repeat(2) { index ->
					put(JSONObject().apply {
						put("HID", "native-high-$index")
						put("MID", "native-mid-$index")
						put("LID", "native-low-$index")
					})
				}
			})
		})
		put("bannerConfig", JSONObject().put("id", "banner-id"))
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
			assertEquals(format, ProbeConfig(
				mod = ProbeMod.valueOf(probe.getString("mod")),
				timeout = probe.getLong("timeout"),
				currency = probe.getString("currency"),
				instances = List(instances.length()) { instanceIndex ->
					val instance = instances.getJSONObject(instanceIndex)
					ProbeInstance(instance.getString("instanceId"), instance.getString("label"),
						instance.getDouble("ecpm"), instance.getString("param"))
				},
			), formats[index].probeConfig)
		}
		val native = config.getJSONObject("nativeConfig")
		val ads = native.getJSONArray("ads")
		assertEquals(NativeConfig(
			timeout = native.getLong("timeout"),
			ads = List(ads.length()) { index ->
				val ad = ads.getJSONObject(index)
				NativeAdConfig(ad.getString("HID"), ad.getString("MID"), ad.getString("LID"))
			},
		), AdmobConfig.nativeConfig)
		assertEquals(BannerConfig(config.getJSONObject("bannerConfig").getString("id")), AdmobConfig.bannerConfig)
		assertEquals(formats.map { it.id } + AdmobConfig.bannerConfig.id, activeIds())
	}

	private fun formatConfigs() = listOf(AdmobConfig.openConfig, AdmobConfig.interConfig, AdmobConfig.videoConfig)
	private fun activeIds() = listOf(AdmobConfig.openID, AdmobConfig.interID, AdmobConfig.videoID, AdmobConfig.bannerID)

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

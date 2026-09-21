package com.mar2sdk.core.ad

import android.content.SharedPreferences
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.common.PreferenceUtil
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class AdConfigTest {

	private val restore = mutableListOf<() -> Unit>()
	private val preferenceValues = mutableMapOf<String, Any?>()

	@Before
	fun setUp() {
		restore += snapshotFields(AdConfig::class.java)
		restore += snapshotFields(PreferenceUtil::class.java)
		PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply {
			isAccessible = true
			set(null, inMemoryPreferences())
		}
	}

	@After
	fun tearDown() {
		restore.asReversed().forEach { it() }
	}

	@Test
	fun readsAllBundledStylesAndScreenAdUnits() {
		val config = bundledConfig()

		AdConfig.applyConfig(config)

		assertEquals(mapOf(
			"testNative1" to AdNativeConfig(NativeStyle.NATIVE_STYLE_1),
			"testNative2" to AdNativeConfig(NativeStyle.NATIVE_STYLE_2),
			"testNative3" to AdNativeConfig(NativeStyle.NATIVE_STYLE_3),
		), AdConfig.adNative)
		assertEquals(mapOf(
			"testBanner1" to AdBannerConfig(BannerStyle.BANNER_STYLE_1),
			"testBanner2" to AdBannerConfig(BannerStyle.BANNER_STYLE_2),
			"testBanner3" to AdBannerConfig(BannerStyle.BANNER_STYLE_3),
		), AdConfig.adBanner)
		assertEquals(config.getJSONObject("ad_units").keys().asSequence().toSet(), AdConfig.adUnits.keys)
		assertEquals(AdFormat.OPEN, AdConfig.adUnits.getValue("app_foreground_open").format)
	}

	@Test
	fun partialUpdatesReplaceEachStyleGroupAndEmptyObjectsClearIt() {
		AdConfig.applyConfig(bundledConfig())
		val originalUnits = AdConfig.adUnits
		val originalBanner = AdConfig.adBanner

		AdConfig.applyConfig(JSONObject("""{"ad_native":{"native-only":{"style":"NATIVE_STYLE_3"}}}"""))

		val expectedNative = mapOf("native-only" to AdNativeConfig(NativeStyle.NATIVE_STYLE_3))
		assertEquals(expectedNative, AdConfig.adNative)
		assertEquals(originalBanner, AdConfig.adBanner)
		assertEquals(originalUnits, AdConfig.adUnits)

		AdConfig.applyConfig(JSONObject("""{"ad_banner":{"banner-only":{"style":"BANNER_STYLE_2"}}}"""))

		val expectedBanner = mapOf("banner-only" to AdBannerConfig(BannerStyle.BANNER_STYLE_2))
		assertEquals(expectedBanner, AdConfig.adBanner)
		assertEquals(expectedNative, AdConfig.adNative)
		assertEquals(originalUnits, AdConfig.adUnits)

		AdConfig.applyConfig(JSONObject("""{"ad_native":{}}"""))
		assertEquals(emptyMap<String, AdNativeConfig>(), AdConfig.adNative)
		assertEquals(expectedBanner, AdConfig.adBanner)

		AdConfig.applyConfig(JSONObject("""{"ad_banner":{}}"""))
		assertEquals(emptyMap<String, AdBannerConfig>(), AdConfig.adBanner)
		assertEquals(emptyMap<String, AdNativeConfig>(), AdConfig.adNative)
		assertEquals(originalUnits, AdConfig.adUnits)
	}

	@Test
	fun persistsAndReloadsStyleGroupsAlongsideScreenAdUnits() {
		AdConfig.applyConfig(bundledConfig())
		val expectedNative = AdConfig.adNative
		val expectedBanner = AdConfig.adBanner
		val expectedUnits = AdConfig.adUnits
		val storedNative = JSONObject(preferenceValues.getValue(AdKey.KEY_AD_NATIVE) as String)
		val storedBanner = JSONObject(preferenceValues.getValue(AdKey.KEY_AD_BANNER) as String)
		assertEquals("NATIVE_STYLE_2", storedNative.getJSONObject("testNative2").getString("style"))
		assertEquals("BANNER_STYLE_3", storedBanner.getJSONObject("testBanner3").getString("style"))
		AdConfig.adNative = emptyMap()
		AdConfig.adBanner = emptyMap()
		AdConfig.adUnits = emptyMap()

		AdConfig.loadConfigFromPreference()

		assertEquals(expectedNative, AdConfig.adNative)
		assertEquals(expectedBanner, AdConfig.adBanner)
		assertEquals(expectedUnits, AdConfig.adUnits)
	}

	@Test
	fun missingStoredStyleGroupsKeepCurrentValues() {
		AdConfig.applyConfig(bundledConfig())
		val expectedNative = AdConfig.adNative
		val expectedBanner = AdConfig.adBanner
		val expectedUnits = AdConfig.adUnits
		preferenceValues.remove(AdKey.KEY_AD_NATIVE)
		preferenceValues.remove(AdKey.KEY_AD_BANNER)

		AdConfig.loadConfigFromPreference()

		assertEquals(expectedNative, AdConfig.adNative)
		assertEquals(expectedBanner, AdConfig.adBanner)
		assertEquals(expectedUnits, AdConfig.adUnits)
	}

	@Test
	fun rejectsUnknownAndMissingStylesInEitherGroup() {
		for (group in listOf("ad_native", "ad_banner")) {
			assertThrows(IllegalArgumentException::class.java) {
				AdConfig.applyConfig(JSONObject().put(group, JSONObject("""{"invalid":{"style":"UNKNOWN"}}""")))
			}
			assertThrows(JSONException::class.java) {
				AdConfig.applyConfig(JSONObject().put(group, JSONObject("""{"invalid":{}}""")))
			}
		}
	}

	private fun bundledConfig(): JSONObject = JSONObject(listOf(
		File("src/main/res/raw/ad_config.json"),
		File("core/src/main/res/raw/ad_config.json"),
	).first { it.isFile }.readText())

	private fun snapshotFields(type: Class<*>): () -> Unit {
		val values = type.declaredFields
			.filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
			.associateWith { it.isAccessible = true; it.get(null) }
		return { values.forEach { (field, value) -> field.set(null, value) } }
	}

	private fun inMemoryPreferences(): SharedPreferences {
		val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
			arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
			when (method.name) {
				"putString", "putInt", "putLong", "putBoolean" -> {
					preferenceValues[args!![0] as String] = args[1]
					proxy
				}
				"commit" -> true
				else -> error("Unexpected editor call: ${method.name}")
			}
		} as SharedPreferences.Editor
		return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
			arrayOf(SharedPreferences::class.java)) { _, method, args ->
			when (method.name) {
				"edit" -> editor
				"getString", "getInt", "getLong", "getBoolean" -> preferenceValues[args!![0] as String] ?: args[1]
				else -> error("Unexpected preferences call: ${method.name}")
			}
		} as SharedPreferences
	}
}

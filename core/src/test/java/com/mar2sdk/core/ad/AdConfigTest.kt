package com.mar2sdk.core.ad

import android.content.SharedPreferences
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.common.PreferenceUtil
import com.mar2sdk.core.common.RiskUtil
import com.mar2sdk.core.common.status.UserType
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.notify.NotificationKey
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdConfigTest {

	private val restore = mutableListOf<() -> Unit>()
	private val preferenceValues = mutableMapOf<String, Any?>()
	private val mainDispatcher = StandardTestDispatcher()

	@Before
	fun setUp() {
		Dispatchers.setMain(mainDispatcher)
		restore += snapshotFields(AdConfig::class.java)
		restore += snapshotFields(PreferenceUtil::class.java)
		PreferenceUtil::class.java.getDeclaredField("sharedPreferences").apply {
			isAccessible = true
			set(null, inMemoryPreferences())
		}
		restore += snapshotFields(Core::class.java)
		restore += snapshotFields(NotificationConfig::class.java)
	}

	@After
	fun tearDown() {
		try {
			mainDispatcher.scheduler.runCurrent()
		} finally {
			restore.asReversed().forEach { it() }
			Dispatchers.resetMain()
		}
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

	@Test
	fun appliesMatchingAdAndNotificationPoliciesForEveryUserTypeAndRiskSwitch() {
		val cases = listOf(
			Triple(UserType.UNKNOW, false, UserType.UNKNOW),
			Triple(UserType.UNKNOW, true, UserType.RISK),
			Triple(UserType.NATURE, false, UserType.NATURE),
			Triple(UserType.NATURE, true, UserType.RISK),
			Triple(UserType.RISK, false, UserType.RISK),
			Triple(UserType.RISK, true, UserType.RISK),
			Triple(UserType.COMMON, false, UserType.COMMON),
			Triple(UserType.COMMON, true, UserType.COMMON),
			Triple(UserType.HIGH_VALUE, false, UserType.HIGH_VALUE),
			Triple(UserType.HIGH_VALUE, true, UserType.HIGH_VALUE),
		)
		val intervals = mapOf(
			UserType.UNKNOW to 11, UserType.NATURE to 22, UserType.RISK to 33,
			UserType.COMMON to 44, UserType.HIGH_VALUE to 55,
		)
		for ((userType, isRisk, expectedType) in cases) {
			Core.userType = userType
			AdConfig.isRisk = !isRisk
			val configs = intervals.flatMap { (type, interval) -> listOf(
				"ad_config_${type.name}" to JSONObject().put("isRisk", isRisk).put("interval", interval),
				"notification_config_${type.name}" to JSONObject().put("interval_second", interval + 100),
			) }.toMap()

			val requested = applyPolicies(configs)

			val message = "$userType, isRisk=$isRisk"
			assertEquals(message, intervals.getValue(expectedType), AdConfig.interval)
			assertEquals(message, intervals.getValue(expectedType) + 100, NotificationConfig.intervalSecond)
			assertEquals(message, expectedType, Core.policyUserType)
			assertEquals(message, userType, Core.userType)
			assertEquals(message, isRisk, AdConfig.isRisk)
			assertEquals(message, listOf("ad_config_${userType.name}", "ad_config_${expectedType.name}")
				.distinct() + "notification_config_${expectedType.name}", requested)
			assertEquals(message, AdConfig.interval, preferenceValues[AdKey.KEY_INTERVAL])
			assertEquals(message, NotificationConfig.intervalSecond, preferenceValues[NotificationKey.KEY_INTERVAL_SECOND])
		}
	}

	@Test
	fun remoteSwitchEnablesAndDisablesRiskPoliciesWithoutRiskConfigOverridingIt() {
		for (userType in listOf(UserType.UNKNOW, UserType.NATURE)) {
			Core.userType = userType
			AdConfig.isRisk = false
			val userConfig = JSONObject().put("isRisk", true).put("interval", 12)
			val riskConfig = JSONObject().put("isRisk", false).put("interval", 90)
			val configs = mapOf(
				"ad_config_${userType.name}" to userConfig,
				"ad_config_RISK" to riskConfig,
				"notification_config_${userType.name}" to JSONObject().put("isSend", true).put("interval_second", 24),
				"notification_config_RISK" to JSONObject().put("isSend", false).put("interval_second", 180),
			)

			repeat(2) {
				assertEquals(listOf("ad_config_${userType.name}", "ad_config_RISK", "notification_config_RISK"),
					applyPolicies(configs))
				assertEquals(90, AdConfig.interval)
				assertEquals(180, NotificationConfig.intervalSecond)
				assertEquals(false, NotificationConfig.isSend)
				assertEquals(true, AdConfig.isRisk)
				assertEquals(true, preferenceValues[AdKey.KEY_IS_RISK])
				assertEquals(UserType.RISK, Core.policyUserType)
				assertEquals(userType, Core.userType)
				assertEquals(false, riskConfig.getBoolean("isRisk"))
			}

			userConfig.put("isRisk", false)
			assertEquals(listOf("ad_config_${userType.name}", "notification_config_${userType.name}"),
				applyPolicies(configs))
			assertEquals(12, AdConfig.interval)
			assertEquals(24, NotificationConfig.intervalSecond)
			assertEquals(true, NotificationConfig.isSend)
			assertEquals(false, AdConfig.isRisk)
			assertEquals(false, preferenceValues[AdKey.KEY_IS_RISK])
			assertEquals(userType, Core.policyUserType)
			assertEquals(userType, Core.userType)
		}
	}

	@Test
	fun missingRemoteRiskFlagRetainsCurrentSwitch() {
		Core.userType = UserType.NATURE
		val configs = mapOf(
			"ad_config_NATURE" to JSONObject().put("interval", 12),
			"ad_config_RISK" to JSONObject().put("isRisk", false).put("interval", 90),
			"notification_config_NATURE" to JSONObject().put("interval_second", 24),
			"notification_config_RISK" to JSONObject().put("interval_second", 180),
		)
		AdConfig.isRisk = true

		assertEquals(listOf("ad_config_NATURE", "ad_config_RISK", "notification_config_RISK"), applyPolicies(configs))
		assertEquals(true, AdConfig.isRisk)
		assertEquals(90, AdConfig.interval)
		assertEquals(180, NotificationConfig.intervalSecond)

		AdConfig.isRisk = false

		assertEquals(listOf("ad_config_NATURE", "notification_config_NATURE"), applyPolicies(configs))
		assertEquals(false, AdConfig.isRisk)
		assertEquals(12, AdConfig.interval)
		assertEquals(24, NotificationConfig.intervalSecond)
	}

	@Test
	fun missingRemoteGroupsRetainCurrentAdAndNotificationValues() {
		Core.userType = UserType.UNKNOW
		AdConfig.isRisk = true
		AdConfig.interval = 17
		NotificationConfig.intervalSecond = 34

		assertEquals(listOf("ad_config_UNKNOW", "ad_config_RISK", "notification_config_RISK"), applyPolicies(emptyMap()))

		assertEquals(true, AdConfig.isRisk)
		assertEquals(17, AdConfig.interval)
		assertEquals(34, NotificationConfig.intervalSecond)
		assertEquals(UserType.RISK, Core.policyUserType)
		assertEquals(UserType.UNKNOW, Core.userType)
		assertEquals(emptyMap<String, Any?>(), preferenceValues)
	}

	@Test
	fun missingRiskGroupRetainsOnlyThatPolicyWhileApplyingAvailableConfigAndSwitch() {
		for (missingKey in listOf("ad_config_RISK", "notification_config_RISK")) {
			Core.userType = UserType.NATURE
			AdConfig.isRisk = false
			AdConfig.interval = 17
			NotificationConfig.intervalSecond = 34
			val configs = mapOf(
				"ad_config_NATURE" to JSONObject().put("isRisk", true).put("interval", 12),
				"ad_config_RISK" to JSONObject().put("interval", 90),
				"notification_config_NATURE" to JSONObject().put("interval_second", 24),
				"notification_config_RISK" to JSONObject().put("interval_second", 180),
			) - missingKey

			assertEquals(listOf("ad_config_NATURE", "ad_config_RISK", "notification_config_RISK"), applyPolicies(configs))

			assertEquals(if (missingKey == "ad_config_RISK") 17 else 90, AdConfig.interval)
			assertEquals(if (missingKey == "notification_config_RISK") 34 else 180, NotificationConfig.intervalSecond)
			assertEquals(true, AdConfig.isRisk)
			assertEquals(true, preferenceValues[AdKey.KEY_IS_RISK])
			assertEquals(UserType.RISK, Core.policyUserType)
			assertEquals(UserType.NATURE, Core.userType)
		}
	}

	@Test
	fun persistsAndRestoresRiskSwitchAndKeepsCurrentValueWhenPreferenceIsAbsent() {
		Core.userType = UserType.NATURE
		for (isRisk in listOf(true, false)) {
			AdConfig.applyConfig(JSONObject().put("isRisk", isRisk))
			assertEquals(isRisk, preferenceValues[AdKey.KEY_IS_RISK])
			AdConfig.isRisk = !isRisk

			AdConfig.loadConfigFromPreference()

			assertEquals(isRisk, AdConfig.isRisk)
			assertEquals(if (isRisk) UserType.RISK else UserType.NATURE, Core.policyUserType)
			preferenceValues.remove(AdKey.KEY_IS_RISK)

			AdConfig.loadConfigFromPreference()

			assertEquals(isRisk, AdConfig.isRisk)
		}
	}

	private fun applyPolicies(configs: Map<String, JSONObject>): List<String> {
		val requested = mutableListOf<String>()
		RiskUtil.applyUserPolicyConfigs { key ->
			requested += key
			configs[key]
		}
		mainDispatcher.scheduler.runCurrent()
		return requested
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

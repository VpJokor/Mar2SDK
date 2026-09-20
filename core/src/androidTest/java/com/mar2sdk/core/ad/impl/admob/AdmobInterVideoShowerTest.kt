package com.mar2sdk.core.ad.impl.admob

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.rewarded.OnAdMetadataChangedListener
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProbeResult
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.AdShowStatus
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.common.PreferenceUtil
import com.mar2sdk.core.log.LogConfig
import kotlin.reflect.KMutableProperty0
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/** 使用模拟广告验证展示器与加载器的实际协作，不发起网络广告请求。 */
@RunWith(AndroidJUnit4::class)
class AdmobInterVideoShowerTest {

	private val restore = mutableListOf<() -> Unit>()
	private val pairShowers: List<suspend (Activity, ShowCallback) -> AdShowStatus> = listOf(
		AdmobShower::showInterVideo,
		AdmobShower::showVideoInter,
	)

	@Before
	fun setUp() = onMain {
		// appMod 可能尚未被其他测试初始化，其底层字段为 null 时也需保留原值。
		val appModField = Core::class.java.getDeclaredField("appMod").apply { isAccessible = true }
		val previousAppMod = appModField.get(null)
		restore += { appModField.set(null, previousAppMod) }
		Core.appMod = AppMod.RELEASE
		replace(AppStatus::isShowingAd, false)
		replace(AdConfig::showMinTime, 0L)
		replace(AdConfig::showMaxTime, 1_000L)
		for ((property, id) in listOf(
			AdmobConfig::openConfig to "fake-open",
			AdmobConfig::interConfig to "fake-inter",
			AdmobConfig::videoConfig to "fake-video",
		)) {
			val config = property.get()
			replace(property, config.copy(id = id, poolSize = 0,
				probeConfig = config.probeConfig.copy(mod = ProbeMod.REFLECT, currency = "USD")))
		}
		// Keep config persistence isolated from the target application's preferences.
		val preferencesField = PreferenceUtil::class.java.getDeclaredField("sharedPreferences")
			.apply { isAccessible = true }
		val previousPreferences = preferencesField.get(null)
		val testPreferences = InstrumentationRegistry.getInstrumentation().context
			.getSharedPreferences("admob_config_change_test", Context.MODE_PRIVATE)
		testPreferences.edit().clear().commit()
		preferencesField.set(null, testPreferences)
		restore += {
			preferencesField.set(null, previousPreferences)
			testPreferences.edit().clear().commit()
		}
		replace(LogConfig::fbEvents, emptyList())
		replace(LogConfig::thEvents, emptyList())
		replace(LogConfig::localEvents, emptyList())
		replace(LogConfig::netEvents, emptyList())
		clearForTest(AdmobLoader.openPool)
		clearForTest(AdmobLoader.interPool)
		clearForTest(AdmobLoader.videoPool)
		replace(AdmobLoader::isLoadingOpen, false)
		replace(AdmobLoader::isLoadingInter, false)
		replace(AdmobLoader::isLoadingVideo, false)
		for (name in listOf("openLoadDeferred", "interLoadDeferred", "videoLoadDeferred",
			"openLoadingAdUnitId", "interLoadingAdUnitId", "videoLoadingAdUnitId")) {
			val field = AdmobLoader::class.java.getDeclaredField(name).apply { isAccessible = true }
			val previous = field.get(null)
			restore += { field.set(null, previous) }
			field.set(null, null)
		}
	}

	@After
	fun tearDown() = onMain {
		restore.asReversed().forEach { it() }
		restore.clear()
	}

	@Test
	fun poolChecksDiscardOldIdsAndKeepCurrentAdsForAllFormats() = onMain {
		val open = FakeOpen()
		val inter = FakeInter()
		val video = FakeVideo()
		cache(FakeOpen("old-open"), 100)
		cache(FakeInter("old-inter"), 100)
		cache(FakeVideo("old-video"), 100)
		cache(open, 10)
		cache(inter, 10)
		cache(video, 10)

		for (format in listOf(AdFormat.OPEN, AdFormat.INTER, AdFormat.VIDEO)) {
			AdmobLoader.checkPool(format)
		}

		assertEquals(setOf(open), AdmobLoader.openPool.keys)
		assertEquals(setOf(inter), AdmobLoader.interPool.keys)
		assertEquals(setOf(video), AdmobLoader.videoPool.keys)
	}

	@Test
	fun configUpdateRemovesOnlyChangedFormatAndCompletesItsPendingLoad() = runBlocking(Dispatchers.Main) {
		val open = FakeOpen()
		val inter = FakeInter()
		val video = FakeVideo()
		cache(open, 10)
		cache(inter, 10)
		cache(video, 10)
		val pending = installPendingLoads()

		AdmobConfig.applyConfig(JSONObject().put("interConfig",
			adUnitJson(AdmobConfig.interConfig.copy(id = "new-inter"))))

		assertEquals("new-inter", AdmobConfig.interID)
		assertEquals(setOf(open), AdmobLoader.openPool.keys)
		assertTrue(AdmobLoader.interPool.isEmpty())
		assertEquals(setOf(video), AdmobLoader.videoPool.keys)
		assertFalse(pending.open.isCompleted)
		assertFalse(pending.video.isCompleted)
		assertTrue(pending.inter.isCompleted)
		val failure = pending.inter.await() as AdmobLoader.InterLoadResult.Failed
		assertEquals("AdUnitChangedException", failure.exception?.javaClass?.simpleName)
		assertNull(loadField("interLoadDeferred").get(null))
		assertFalse(AdmobLoader.isLoadingInter)
		assertSame(pending.open, loadField("openLoadDeferred").get(null))
		assertSame(pending.video, loadField("videoLoadDeferred").get(null))
		assertTrue(AdmobLoader.isLoadingOpen)
		assertTrue(AdmobLoader.isLoadingVideo)
	}

	@Test
	fun configUpdateClearsAllChangedPoolsAndPendingLoads() = runBlocking(Dispatchers.Main) {
		cache(FakeOpen(), 10)
		cache(FakeInter(), 10)
		cache(FakeVideo(), 10)
		val pending = installPendingLoads()

		AdmobConfig.applyConfig(JSONObject()
			.put("openConfig", adUnitJson(AdmobConfig.openConfig.copy(id = "new-open")))
			.put("interConfig", adUnitJson(AdmobConfig.interConfig.copy(id = "new-inter")))
			.put("videoConfig", adUnitJson(AdmobConfig.videoConfig.copy(id = "new-video"))))

		assertTrue(AdmobLoader.openPool.isEmpty())
		assertTrue(AdmobLoader.interPool.isEmpty())
		assertTrue(AdmobLoader.videoPool.isEmpty())
		assertTrue(pending.open.isCompleted)
		assertTrue(pending.inter.isCompleted)
		assertTrue(pending.video.isCompleted)
		val errors = listOf(
			(pending.open.await() as AdmobLoader.OpenLoadResult.Failed).exception,
			(pending.inter.await() as AdmobLoader.InterLoadResult.Failed).exception,
			(pending.video.await() as AdmobLoader.VideoLoadResult.Failed).exception,
		)
		errors.forEach { assertEquals("AdUnitChangedException", it?.javaClass?.simpleName) }
		assertFalse(AdmobLoader.isLoadingOpen)
		assertFalse(AdmobLoader.isLoadingInter)
		assertFalse(AdmobLoader.isLoadingVideo)
	}

	@Test
	fun pendingLoadsWithoutRecordedIdsAreRejectedForAllFormats() = runBlocking(Dispatchers.Main) {
		val pending = installPendingLoads()
		for (format in listOf(AdFormat.OPEN, AdFormat.INTER, AdFormat.VIDEO)) {
			loadField("${format.name.lowercase()}LoadingAdUnitId").set(null, null)
			AdmobLoader.checkPool(format)
			assertNull(loadField("${format.name.lowercase()}LoadDeferred").get(null))
			assertFalse(isLoading(format))
		}
		assertTrue(pending.open.isCompleted)
		assertTrue(pending.inter.isCompleted)
		assertTrue(pending.video.isCompleted)
		assertTrue((pending.open.await() as AdmobLoader.OpenLoadResult.Failed).exception
			is AdmobLoader.AdUnitChangedException)
		assertTrue((pending.inter.await() as AdmobLoader.InterLoadResult.Failed).exception
			is AdmobLoader.AdUnitChangedException)
		assertTrue((pending.video.await() as AdmobLoader.VideoLoadResult.Failed).exception
			is AdmobLoader.AdUnitChangedException)
	}

	@Test
	fun parameterUpdatesWithUnchangedIdsKeepCachedAdsAndPendingLoads() = onMain {
		val open = FakeOpen()
		val inter = FakeInter()
		val video = FakeVideo()
		cache(open, 10)
		cache(inter, 10)
		cache(video, 10)
		val pending = installPendingLoads()

		AdmobConfig.applyConfig(JSONObject()
			.put("openConfig", adUnitJson(AdmobConfig.openConfig.copy(timeout = 60_000L)))
			.put("interConfig", adUnitJson(AdmobConfig.interConfig.copy(timeout = 60_000L)))
			.put("videoConfig", adUnitJson(AdmobConfig.videoConfig.copy(timeout = 60_000L))))

		assertEquals(setOf(open), AdmobLoader.openPool.keys)
		assertEquals(setOf(inter), AdmobLoader.interPool.keys)
		assertEquals(setOf(video), AdmobLoader.videoPool.keys)
		assertFalse(pending.open.isCompleted)
		assertFalse(pending.inter.isCompleted)
		assertFalse(pending.video.isCompleted)
		assertSame(pending.open, loadField("openLoadDeferred").get(null))
		assertSame(pending.inter, loadField("interLoadDeferred").get(null))
		assertSame(pending.video, loadField("videoLoadDeferred").get(null))
		assertTrue(AdmobLoader.isLoadingOpen)
		assertTrue(AdmobLoader.isLoadingInter)
		assertTrue(AdmobLoader.isLoadingVideo)
	}

	@Test
	fun malformedUpdateDoesNotPartiallySwitchIdsOrDiscardAds() = onMain {
		val previousOpen = AdmobConfig.openConfig
		val previousInter = AdmobConfig.interConfig
		val open = FakeOpen()
		val inter = FakeInter()
		cache(open, 10)
		cache(inter, 10)
		val pending = installPendingLoads()

		val result = runCatching {
			AdmobConfig.applyConfig(JSONObject()
				.put("openConfig", adUnitJson(previousOpen.copy(id = "new-open")))
				.put("interConfig", JSONObject().put("id", "invalid-inter")))
		}

		assertTrue(result.isFailure)
		assertEquals(previousOpen, AdmobConfig.openConfig)
		assertEquals(previousInter, AdmobConfig.interConfig)
		assertEquals(setOf(open), AdmobLoader.openPool.keys)
		assertEquals(setOf(inter), AdmobLoader.interPool.keys)
		assertFalse(pending.open.isCompleted)
		assertFalse(pending.inter.isCompleted)
	}

	@Test
	fun lateSdkCallbacksCannotCacheOldAdsOrCompleteReplacementLoads() = runBlocking(Dispatchers.Main) {
		val requests = captureAdRequests()
		for (format in listOf(AdFormat.OPEN, AdFormat.INTER, AdFormat.VIDEO)) {
			requests.clear()
			val property = configProperty(format)
			property.set(property.get().copy(poolSize = 1))
			// A caller can retain a context from before the update; loading must use the current ID.
			val context = ScreenAdContext(adFormat = format, adPlatform = AdPlatform.ADMOB,
				trigger = ScreenAdTrigger.ENTER, adUnitId = "caller-stale-id")
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				when (format) {
					AdFormat.OPEN -> AdmobLoader.loadOpenResult(context)
					AdFormat.INTER -> AdmobLoader.loadInterResult(context)
					else -> AdmobLoader.loadVideoResult(context)
				}
			}
			try {
				assertEquals(1, requests.size)
				val oldRequest = requests.single()
				assertEquals(property.get().id, oldRequest.id)
				AdmobConfig.applyConfig(JSONObject().put(property.name,
					adUnitJson(property.get().copy(id = "replacement-${format.name}"))))
				yield()
				assertEquals(2, requests.size)
				val replacement = requests.last()
				assertEquals(property.get().id, replacement.id)
				val deferredField = loadField("${format.name.lowercase()}LoadDeferred")
				val replacementDeferred = deferredField.get(null)
				assertNotNull(replacementDeferred)

				oldRequest.succeed()

				assertTrue(poolAds(format).isEmpty())
				assertSame(replacementDeferred, deferredField.get(null))
				assertTrue(isLoading(format))
				assertFalse(waiting.isCompleted)
				replacement.succeed()
				assertSame(replacement.ad, loadedAd(waiting.await()))
				assertEquals(setOf(replacement.ad), poolAds(format))
				assertNull(deferredField.get(null))
				assertFalse(isLoading(format))
			} finally {
				waiting.cancelAndJoin()
			}
		}
	}

	@Test
	fun returningToOriginalIdStillRejectsItsFirstInFlightRequest() = runBlocking(Dispatchers.Main) {
		val requests = captureAdRequests()
		AdmobConfig.interConfig = AdmobConfig.interConfig.copy(poolSize = 1)
		val waiting = async(start = CoroutineStart.UNDISPATCHED) { AdmobLoader.loadInterResult() }
		try {
			val firstA = requests.single()
			AdmobConfig.applyConfig(JSONObject().put("interConfig",
				adUnitJson(AdmobConfig.interConfig.copy(id = "inter-b"))))
			yield()
			assertEquals(2, requests.size)
			val requestB = requests.last()
			AdmobConfig.applyConfig(JSONObject().put("interConfig",
				adUnitJson(AdmobConfig.interConfig.copy(id = firstA.id))))
			yield()
			assertEquals(3, requests.size)
			val secondA = requests.last()
			assertEquals(firstA.id, secondA.id)
			val currentDeferred = loadField("interLoadDeferred").get(null)

			firstA.succeed()
			requestB.succeed()

			assertTrue(AdmobLoader.interPool.isEmpty())
			assertSame(currentDeferred, loadField("interLoadDeferred").get(null))
			assertTrue(AdmobLoader.isLoadingInter)
			assertFalse(waiting.isCompleted)
			secondA.succeed()
			assertSame(secondA.ad, (waiting.await() as AdmobLoader.InterLoadResult.Loaded).ad)
			assertEquals(setOf(secondA.ad), AdmobLoader.interPool.keys)
			assertFalse(AdmobLoader.isLoadingInter)
		} finally {
			waiting.cancelAndJoin()
		}
	}

	@Test
	fun singleFormatShowersRejectIdsChangedDuringMinimumWait() = runBlocking(Dispatchers.Main) {
		for (format in listOf(AdFormat.OPEN, AdFormat.INTER, AdFormat.VIDEO)) {
			AdConfig.showMinTime = 100L
			val open = FakeOpen()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(open, 10)
			cache(inter, 10)
			cache(video, 10)
			val callback = RecordingCallback(format)
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				when (format) {
					AdFormat.OPEN -> AdmobShower.showOpen(Activity(), callback)
					AdFormat.INTER -> AdmobShower.showInter(Activity(), callback)
					else -> AdmobShower.showVideo(Activity(), callback)
				}
			}
			try {
				assertTrue(AppStatus.isShowingAd)
				assertFalse(waiting.isCompleted)
				val (key, config) = when (format) {
					AdFormat.OPEN -> "openConfig" to AdmobConfig.openConfig
					AdFormat.INTER -> "interConfig" to AdmobConfig.interConfig
					else -> "videoConfig" to AdmobConfig.videoConfig
				}
				AdmobConfig.applyConfig(JSONObject().put(key, adUnitJson(config.copy(id = "new-${format.name}"))))

				assertEquals(AdShowStatus.SHOW_FAIL, waiting.await())
				assertEquals(listOf(ShowFailResult.AD_CONFIG_CHANGED), callback.failures)
				assertFalse(AppStatus.isShowingAd)
				assertEquals(0, open.shows)
				assertEquals(0, inter.shows)
				assertEquals(0, video.shows)
			} finally {
				waiting.cancelAndJoin()
			}
		}
	}

	@Test
	fun pairedShowersReplaceIdChangedWinnerAfterMinimumWait() = runBlocking(Dispatchers.Main) {
		for (showPair in pairShowers) {
			AdmobConfig.interConfig = AdmobConfig.interConfig.copy(id = "fake-inter")
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			AdConfig.showMinTime = 100L
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, 20)
			cache(video, 10)
			val callback = RecordingCallback()
			val waiting = async(start = CoroutineStart.UNDISPATCHED) { showPair(Activity(), callback) }
			try {
				assertTrue(AppStatus.isShowingAd)
				assertFalse(waiting.isCompleted)
				AdmobConfig.applyConfig(JSONObject().put("interConfig",
					adUnitJson(AdmobConfig.interConfig.copy(id = "new-inter"))))

				assertEquals(AdShowStatus.SHOW_SUCCESS, waiting.await())
				assertEquals(0, inter.shows)
				assertEquals(1, video.shows)
				assertEquals("fake-video", callback.adContext.adUnitId)
				assertTrue(callback.failures.isEmpty())
				video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
				assertFalse(AppStatus.isShowingAd)
			} finally {
				waiting.cancelAndJoin()
			}
		}
	}

	@Test
	fun sameAdUnitInstancesKeepIndependentPrices() = onMain {
		val first = FakeInter()
		val second = FakeInter()
		cache(first, 10)
		cache(second, 20)

		assertEquals(AdmobPrice(10, "USD", 1), first.reflectPrice)
		assertEquals(AdmobPrice(20, "USD", 1), second.reflectPrice)
		first.reflectPrice = null
		assertNull(first.reflectPrice)
		assertEquals(AdmobPrice(20, "USD", 1), second.reflectPrice)
	}

	@Test
	fun allFormatsKeepProbeResultsAndBoundsConsistentPerAd() = onMain {
		val open = FakeOpen()
		val inter = FakeInter()
		val secondInter = FakeInter()
		val video = FakeVideo()
		val properties = listOf(
			Triple(open::adapterProbeResult, open::adapterHPrice, open::adapterLPrice),
			Triple(inter::adapterProbeResult, inter::adapterHPrice, inter::adapterLPrice),
			Triple(secondInter::adapterProbeResult, secondInter::adapterHPrice, secondInter::adapterLPrice),
			Triple(video::adapterProbeResult, video::adapterHPrice, video::adapterLPrice),
		)
		val result = AdmobAdapterProbeResult(
			AdmobAdapterProbeResult.Status.BOUNDED, AdmobConfig.interConfig.probeConfig,
			hPrice = 30_000_000, lPrice = 20_000_000,
		)
		properties.forEachIndexed { index, (snapshot, _, _) ->
			snapshot.set(result.copy(hPrice = 30_000_000L + index))
		}
		properties.forEachIndexed { index, (snapshot, high, low) ->
			assertEquals(30_000_000L + index, high.get())
			assertEquals(20_000_000L, low.get())
			val replacement = result.copy(hPrice = 40_000_000, lPrice = 10_000_000)
			snapshot.set(replacement)
			assertSame(replacement, snapshot.get())
			assertEquals(40_000_000L, high.get())
			assertEquals(10_000_000L, low.get())
			val upperOnly = result.copy(status = AdmobAdapterProbeResult.Status.UPPER_BOUND_ONLY, lPrice = null)
			snapshot.set(upperOnly)
			assertSame(upperOnly, snapshot.get())
			assertEquals(30_000_000L, high.get())
			assertNull(low.get())
			snapshot.set(null)
			assertNull(snapshot.get())
			assertNull(high.get())
			assertNull(low.get())
		}
	}

	@Test
	fun formatsWithoutProbeSnapshotsHaveUnknownPricesDespiteCurrentConfigAndReflectedPrices() = onMain {
		val open = FakeOpen()
		val inter = FakeInter()
		val video = FakeVideo()
		replace(open::reflectPrice, AdmobPrice(20_000, "USD", 1))
		replace(inter::reflectPrice, AdmobPrice(20_000, "USD", 1))
		replace(video::reflectPrice, AdmobPrice(20_000, "USD", 1))
		for (mode in ProbeMod.entries) {
			for (property in listOf(AdmobConfig::openConfig, AdmobConfig::interConfig, AdmobConfig::videoConfig)) {
				property.set(property.get().copy(probeConfig = probeConfig(mode)))
			}
			for (ad in listOf(open, inter, video)) {
				assertNull("No load snapshot for ${ad.javaClass.simpleName}; current mode=$mode",
					comparisonPriceEcpmMicros(ad))
			}
		}
	}

	@Test
	fun loadedProbeSnapshotsKeepTheirModeAndCurrencyAfterConfigChangesForAllFormats() = onMain {
		val open = FakeOpen()
		val inter = FakeInter()
		val video = FakeVideo()
		cache(open, 20_000)
		cache(inter, 20_000)
		cache(video, 20_000)
		val properties = listOf(
			(open as Any) to open::adapterProbeResult,
			(inter as Any) to inter::adapterProbeResult,
			(video as Any) to video::adapterProbeResult,
		)
		properties.forEach { (ad, snapshot) ->
			snapshot.set(bounds(ProbeMod.ADAPTER_H, 30_000_000, 10_000_000))
			// 当前广告格式配置为 REFLECT，该广告加载时使用的模式为 ADAPTER_H。
			assertEquals(30_000_000L, comparisonPriceEcpmMicros(ad))
		}
		for (property in listOf(AdmobConfig::openConfig, AdmobConfig::interConfig, AdmobConfig::videoConfig)) {
			property.set(property.get().copy(probeConfig = probeConfig(ProbeMod.ADAPTER_L).copy(currency = "EUR")))
		}
		properties.forEach { (ad, snapshot) ->
			assertEquals(30_000_000L, comparisonPriceEcpmMicros(ad))
			snapshot.set(bounds(ProbeMod.REFLECT, 30_000_000, 10_000_000))
			assertEquals(20_000_000L, comparisonPriceEcpmMicros(ad))
			snapshot.set(null)
			assertNull(comparisonPriceEcpmMicros(ad))
		}
	}

	@Test
	fun pairShowersSelectHighestAdapterCandidateAgainstReflectedVideo() = runBlocking(Dispatchers.Main) {
		for (mode in listOf(ProbeMod.ADAPTER_H, ProbeMod.ADAPTER_M, ProbeMod.ADAPTER_L)) {
			for (showPair in pairShowers) {
				AdmobLoader.interPool.clear()
				AdmobLoader.videoPool.clear()
				val lowerInter = FakeInter()
				val winningInter = FakeInter()
				val video = FakeVideo()
				cache(lowerInter, 100_000)
				cache(winningInter, 1)
				cache(video, 15_000)
				lowerInter.adapterProbeResult = bounds(mode, 12_000_000, 8_000_000)
				winningInter.adapterProbeResult = bounds(mode, 60_000_000, 20_000_000)

				assertEquals(AdShowStatus.SHOW_SUCCESS, showPair(Activity(), RecordingCallback()))
				assertEquals(mode.name, 1, winningInter.shows)
				assertEquals(0, lowerInter.shows)
				assertEquals(0, video.shows)
				assertEquals(setOf(lowerInter), AdmobLoader.interPool.keys)
				winningInter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			}
		}
	}

	@Test
	fun reflectedVideoCanBeatAdapterLowerBoundAndTieItsMidpoint() = runBlocking(Dispatchers.Main) {
		for (mode in listOf(ProbeMod.ADAPTER_H, ProbeMod.ADAPTER_M, ProbeMod.ADAPTER_L)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, 100_000)
			cache(video, 25_000)
			inter.adapterProbeResult = bounds(mode, 30_000_000, 20_000_000)
			val callback = RecordingCallback()

			assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showInterVideo(Activity(), callback))
			val videoWins = mode == ProbeMod.ADAPTER_L
			assertEquals(mode.name, if (videoWins) 0 else 1, inter.shows)
			assertEquals(mode.name, if (videoWins) 1 else 0, video.shows)
			assertEquals(if (videoWins) AdFormat.VIDEO else AdFormat.INTER, callback.adContext.adFormat)
			if (videoWins) video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			else inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		}
	}

	@Test
	fun openInterComparesMixedModesAndSelectsHighestCandidateInEitherPool() = runBlocking(Dispatchers.Main) {
		for (openWins in listOf(true, false)) {
			AdmobLoader.openPool.clear()
			AdmobLoader.interPool.clear()
			val lowerOpen = FakeOpen()
			val open = FakeOpen()
			val lowerInter = FakeInter()
			val inter = FakeInter()
			cache(lowerOpen, if (openWins) 100_000 else 10_000)
			cache(open, if (openWins) 1 else 20_000)
			cache(lowerInter, if (openWins) 10_000 else 100_000)
			cache(inter, if (openWins) 20_000 else 1)
			if (openWins) {
				lowerOpen.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 15_000_000, 5_000_000)
				open.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 40_000_000, 20_000_000)
			} else {
				lowerInter.adapterProbeResult = bounds(ProbeMod.ADAPTER_L, 40_000_000, 10_000_000)
				inter.adapterProbeResult = bounds(ProbeMod.ADAPTER_L, 40_000_000, 30_000_000)
			}
			val callback = RecordingCallback(AdFormat.OPEN_INTER)

			assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showOpenInter(Activity(), callback))
			assertEquals(if (openWins) 1 else 0, open.shows)
			assertEquals(if (openWins) 0 else 1, inter.shows)
			assertEquals(0, lowerOpen.shows)
			assertEquals(0, lowerInter.shows)
			assertEquals(if (openWins) AdFormat.OPEN else AdFormat.INTER, callback.adContext.adFormat)
			if (openWins) open.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			else inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		}
	}

	@Test
	fun pairShowersPreservePrimaryPreferenceWhenAdapterPriceIsMissingOrEqual() = runBlocking(Dispatchers.Main) {
		for (equalPrice in listOf(false, true)) {
			AdmobLoader.openPool.clear()
			AdmobLoader.interPool.clear()
			val open = FakeOpen()
			val secondaryInter = FakeInter()
			cache(open, 1)
			cache(secondaryInter, 100_000)
			open.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 30_000_000, if (equalPrice) 10_000_000 else null)
			secondaryInter.adapterProbeResult = bounds(ProbeMod.ADAPTER_L, 30_000_000, 20_000_000)
			assertEquals(AdShowStatus.SHOW_SUCCESS,
				AdmobShower.showOpenInter(Activity(), RecordingCallback(AdFormat.OPEN_INTER)))
			assertEquals(1, open.shows)
			assertEquals(0, secondaryInter.shows)
			open.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			for (showPair in pairShowers) {
				AdmobLoader.interPool.clear()
				AdmobLoader.videoPool.clear()
				val inter = FakeInter()
				val video = FakeVideo()
				cache(inter, 1)
				cache(video, 100_000)
				inter.adapterProbeResult = bounds(ProbeMod.ADAPTER_H, if (equalPrice) 20_000_000 else null, 10_000_000)
				video.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 30_000_000, 10_000_000)

				assertEquals(AdShowStatus.SHOW_SUCCESS, showPair(Activity(), RecordingCallback()))
				val interFirst = showPair == pairShowers.first()
				assertEquals(if (interFirst) 1 else 0, inter.shows)
				assertEquals(if (interFirst) 0 else 1, video.shows)
				if (interFirst) inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
				else video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			}
		}
	}

	@Test
	fun singleFormatShowersSelectTheHighestConfiguredAdapterPrice() = runBlocking(Dispatchers.Main) {
		val lowerOpen = FakeOpen()
		val open = FakeOpen()
		val lowerInter = FakeInter()
		val inter = FakeInter()
		val lowerVideo = FakeVideo()
		val video = FakeVideo()
		cache(lowerOpen, 100_000)
		cache(open, 1)
		cache(lowerInter, 100_000)
		cache(inter, 1)
		cache(lowerVideo, 100_000)
		cache(video, 1)
		lowerOpen.adapterProbeResult = bounds(ProbeMod.ADAPTER_H, 20_000_000, 10_000_000)
		open.adapterProbeResult = bounds(ProbeMod.ADAPTER_H, 30_000_000, 10_000_000)
		lowerInter.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 20_000_000, 10_000_000)
		inter.adapterProbeResult = bounds(ProbeMod.ADAPTER_M, 30_000_000, 20_000_000)
		lowerVideo.adapterProbeResult = bounds(ProbeMod.ADAPTER_L, 20_000_000, 10_000_000)
		video.adapterProbeResult = bounds(ProbeMod.ADAPTER_L, 30_000_000, 20_000_000)

		assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showOpen(Activity(), RecordingCallback(AdFormat.OPEN)))
		assertEquals(1, open.shows)
		assertEquals(0, lowerOpen.shows)
		open.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showInter(Activity(), RecordingCallback(AdFormat.INTER)))
		assertEquals(1, inter.shows)
		assertEquals(0, lowerInter.shows)
		inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showVideo(Activity(), RecordingCallback(AdFormat.VIDEO)))
		assertEquals(1, video.shows)
		assertEquals(0, lowerVideo.shows)
		video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
	}

	@Test
	fun emptySdkResponseDoesNotProduceProbePrices() {
		val config = AdmobConfig.interConfig.probeConfig.copy(instances = listOf(
			ProbeInstance("probe", "Probe_30", 30.0, ""),
		))
		val result = AdmobAdapterProxyReader.read(emptyResponseInfo(), config)
		assertEquals(AdmobAdapterProbeResult.Status.MISSING_WINNER, result.status)
		assertNull(result.hPrice)
		assertNull(result.lPrice)
	}

	@Test
	fun highestInterstitialWinsAndOnlyWinningAdIsConsumed() = runBlocking(Dispatchers.Main) {
		for (showPair in pairShowers) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val lowerInter = FakeInter()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(lowerInter, 10)
			cache(inter, 30)
			cache(video, 20)
			val callback = RecordingCallback()

			assertEquals(AdShowStatus.SHOW_SUCCESS, showPair(Activity(), callback))

			assertEquals(1, inter.shows)
			assertEquals(0, lowerInter.shows)
			assertEquals(0, video.shows)
			assertEquals(setOf(lowerInter), AdmobLoader.interPool.keys)
			assertEquals(setOf(video), AdmobLoader.videoPool.keys)
			assertEquals(AdFormat.INTER, callback.adContext.adFormat)
			assertEquals("fake-inter", callback.adContext.adUnitId)
			assertNotNull(inter.onPaidEventListener)
			assertTrue(AppStatus.isShowingAd)
			inter.fullScreenContentCallback!!.onAdImpression()
			assertEquals(1, callback.successes)
			inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			assertEquals(1, callback.closes)
			assertFalse(AppStatus.isShowingAd)
		}
	}

	@Test
	fun higherVideoWinsAndForwardsRewardAndCloseOnce() = runBlocking(Dispatchers.Main) {
		val inter = FakeInter()
		val video = FakeVideo()
		cache(inter, 10)
		cache(video, 20)
		val callback = RecordingCallback()

		assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showInterVideo(Activity(), callback))

		assertEquals(0, inter.shows)
		assertEquals(1, video.shows)
		assertEquals(setOf(inter), AdmobLoader.interPool.keys)
		assertTrue(AdmobLoader.videoPool.isEmpty())
		assertEquals(AdFormat.VIDEO, callback.adContext.adFormat)
		assertEquals("fake-video", callback.adContext.adUnitId)
		assertNotNull(video.onPaidEventListener)
		video.fullScreenContentCallback!!.onAdImpression()
		video.rewardListener!!.onUserEarnedReward(RewardItem.DEFAULT_REWARD)
		assertEquals(1, callback.successes)
		assertEquals(1, callback.rewards)
		assertTrue(AppStatus.isShowingAd)
		video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		assertEquals(1, callback.closes)
		assertFalse(AppStatus.isShowingAd)
	}

	@Test
	fun equalOrUnknownPricesPreferInterstitial() = runBlocking(Dispatchers.Main) {
		for ((interPrice, videoPrice) in listOf(10L to 10L, null to 20L, 10L to null, null to null)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, interPrice)
			cache(video, videoPrice)

			assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showInterVideo(Activity(), RecordingCallback()))
			assertEquals("Prices: $interPrice / $videoPrice", 1, inter.shows)
			assertEquals(0, video.shows)
			inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		}
	}

	@Test
	fun equalOrUnknownPricesPreferVideoAndForwardReward() = runBlocking(Dispatchers.Main) {
		for ((interPrice, videoPrice) in listOf(10L to 10L, null to 20L, 10L to null, null to null)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, interPrice)
			cache(video, videoPrice)
			val callback = RecordingCallback(AdFormat.VIDEO_INTER)

			assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showVideoInter(Activity(), callback))
			assertEquals("Prices: $interPrice / $videoPrice", 1, video.shows)
			assertEquals(0, inter.shows)
			assertEquals(setOf(inter), AdmobLoader.interPool.keys)
			assertTrue(AdmobLoader.videoPool.isEmpty())
			assertEquals(AdFormat.VIDEO, callback.adContext.adFormat)
			video.rewardListener!!.onUserEarnedReward(RewardItem.DEFAULT_REWARD)
			assertEquals(1, callback.rewards)
			video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			assertEquals(1, callback.closes)
			assertFalse(AppStatus.isShowingAd)
		}
	}

	@Test
	fun videoFirstSelectsHighestVideoAndConsumesOnlyWinner() = runBlocking(Dispatchers.Main) {
		val inter = FakeInter()
		val lowerVideo = FakeVideo()
		val winningVideo = FakeVideo()
		cache(inter, 20)
		cache(lowerVideo, 10)
		cache(winningVideo, 30)
		val callback = RecordingCallback(AdFormat.VIDEO_INTER)

		assertEquals(AdShowStatus.SHOW_SUCCESS, AdmobShower.showVideoInter(Activity(), callback))

		assertEquals(0, inter.shows)
		assertEquals(0, lowerVideo.shows)
		assertEquals(1, winningVideo.shows)
		assertEquals(setOf(inter), AdmobLoader.interPool.keys)
		assertEquals(setOf(lowerVideo), AdmobLoader.videoPool.keys)
		assertEquals(AdFormat.VIDEO, callback.adContext.adFormat)
		assertEquals("fake-video", callback.adContext.adUnitId)
		winningVideo.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
	}

	@Test
	fun coreRoutesVideoInterToVideoFirstSelection() = runBlocking(Dispatchers.Main) {
		val inter = FakeInter()
		val video = FakeVideo()
		cache(inter, 10)
		cache(video, 10)
		val callback = RecordingCallback(AdFormat.VIDEO_INTER)

		assertEquals(AdShowStatus.SHOW_SUCCESS, Core.showAd(Activity(), callback))

		assertEquals(0, inter.shows)
		assertEquals(1, video.shows)
		assertEquals(AdFormat.VIDEO, callback.adContext.adFormat)
		video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
	}

	@Test
	fun videoFirstWaitsForMissingFormatAndComparesLoadedAd() = runBlocking(Dispatchers.Main) {
		for (missingVideo in listOf(false, true)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			if (missingVideo) cache(inter, 10) else cache(video, 10)
			val pendingInter = CompletableDeferred<AdmobLoader.InterLoadResult>()
			val pendingVideo = CompletableDeferred<AdmobLoader.VideoLoadResult>()
			val deferredField = AdmobLoader::class.java.getDeclaredField(
				if (missingVideo) "videoLoadDeferred" else "interLoadDeferred"
			).apply { isAccessible = true }
			val loadingIdField = loadField(if (missingVideo) "videoLoadingAdUnitId" else "interLoadingAdUnitId")
			deferredField.set(null, if (missingVideo) pendingVideo else pendingInter)
			loadingIdField.set(null, if (missingVideo) AdmobConfig.videoID else AdmobConfig.interID)
			val callback = RecordingCallback(AdFormat.VIDEO_INTER)
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				AdmobShower.showVideoInter(Activity(), callback)
			}
			try {
				yield()
				assertFalse("Must wait for missing format; missingVideo=$missingVideo", waiting.isCompleted)
				assertTrue(AppStatus.isShowingAd)
				// 模拟加载器在发布已加载广告前清除正在进行的加载任务引用。
				deferredField.set(null, null)
				loadingIdField.set(null, null)
				if (missingVideo) {
					cache(video, 20)
					pendingVideo.complete(AdmobLoader.VideoLoadResult.Loaded(video))
				} else {
					cache(inter, 20)
					pendingInter.complete(AdmobLoader.InterLoadResult.Loaded(inter))
				}
				assertEquals(AdShowStatus.SHOW_SUCCESS, waiting.await())
				assertEquals(if (missingVideo) 0 else 1, inter.shows)
				assertEquals(if (missingVideo) 1 else 0, video.shows)
				assertEquals(if (missingVideo) AdFormat.VIDEO else AdFormat.INTER, callback.adContext.adFormat)
				if (missingVideo) video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
				else inter.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
			} finally {
				deferredField.set(null, null)
				loadingIdField.set(null, null)
				waiting.cancelAndJoin()
			}
		}
	}

	@Test
	fun synchronousShowFailureReleasesLockAndIsReportedOnceForEitherFormat() = runBlocking(Dispatchers.Main) {
		for (videoWins in listOf(false, true)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, if (videoWins) 10 else 20)
			cache(video, if (videoWins) 20 else 10)
			val callback = RecordingCallback()
			val error = AdError(0, "fake show failure", "test")
			inter.onShow = { inter.fullScreenContentCallback!!.onAdFailedToShowFullScreenContent(error) }
			video.onShow = { video.fullScreenContentCallback!!.onAdFailedToShowFullScreenContent(error) }

			assertEquals(AdShowStatus.SHOW_FAIL, AdmobShower.showInterVideo(Activity(), callback))
			val content = if (videoWins) video.fullScreenContentCallback!! else inter.fullScreenContentCallback!!
			content.onAdFailedToShowFullScreenContent(error)
			content.onAdDismissedFullScreenContent()
			assertEquals(listOf(ShowFailResult.FAILED_TO_SHOW_CONTENT), callback.failures)
			assertEquals(0, callback.closes)
			assertFalse(AppStatus.isShowingAd)
		}
	}

	@Test
	fun thrownShowExceptionReleasesLockForEitherFormat() = runBlocking(Dispatchers.Main) {
		for (videoWins in listOf(false, true)) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, if (videoWins) 10 else 20)
			cache(video, if (videoWins) 20 else 10)
			inter.onShow = { throw IllegalStateException("fake interstitial show exception") }
			video.onShow = { throw IllegalStateException("fake rewarded show exception") }
			val callback = RecordingCallback()

			assertEquals(AdShowStatus.SHOW_FAIL, AdmobShower.showInterVideo(Activity(), callback))
			assertEquals(listOf(ShowFailResult.SHOW_AD_EXCEPTION), callback.failures)
			assertFalse(AppStatus.isShowingAd)
			assertEquals(if (videoWins) 1 else 0, AdmobLoader.interPool.size)
			assertEquals(if (videoWins) 0 else 1, AdmobLoader.videoPool.size)
		}
	}

	@Test
	fun minimumWaitBlocksOtherFormatsAndCancellationReleasesLockWithoutConsumingAds() = runBlocking(Dispatchers.Main) {
		for (showPair in pairShowers) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			AdConfig.showMinTime = 60_000L
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, 20)
			cache(video, 10)
			val callback = RecordingCallback()
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				showPair(Activity(), callback)
			}
			try {
				assertTrue(AppStatus.isShowingAd)
				val otherCallback = RecordingCallback()
				assertEquals(AdShowStatus.OTHER_AD_IS_SHOWING, AdmobShower.showOpenInter(Activity(), otherCallback))
				assertEquals(listOf(ShowFailResult.OTHER_AD_IS_SHOWING), otherCallback.failures)
				assertTrue(AppStatus.isShowingAd)
			} finally {
				waiting.cancelAndJoin()
			}
			assertFalse(AppStatus.isShowingAd)
			assertEquals(0, inter.shows)
			assertEquals(0, video.shows)
			assertEquals(setOf(inter), AdmobLoader.interPool.keys)
			assertEquals(setOf(video), AdmobLoader.videoPool.keys)
			assertTrue(callback.failures.isEmpty())
		}
	}

	@Test
	fun expiredWinnerIsReplacedByRemainingVideoAfterMinimumWait() = runBlocking(Dispatchers.Main) {
		for (showPair in pairShowers) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			AdConfig.showMinTime = 100L
			val inter = FakeInter()
			val video = FakeVideo()
			cache(inter, 20)
			cache(video, 10)
			val callback = RecordingCallback()
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				showPair(Activity(), callback)
			}
			try {
				assertTrue(AppStatus.isShowingAd)
				assertEquals(0, inter.shows)
				AdmobLoader.interPool[inter] = System.currentTimeMillis() - AdmobConfig.interConfig.timeout - 1L
				assertEquals(AdShowStatus.SHOW_SUCCESS, waiting.await())
				assertEquals(0, inter.shows)
				assertEquals(1, video.shows)
				assertEquals(AdFormat.VIDEO, callback.adContext.adFormat)
				assertEquals("fake-video", callback.adContext.adUnitId)
			} finally {
				waiting.cancelAndJoin()
			}
			video.fullScreenContentCallback!!.onAdDismissedFullScreenContent()
		}
	}

	@Test
	fun emptyPoolsWithNoWaitReturnTimeoutAndReleaseLock() = runBlocking(Dispatchers.Main) {
		for (showPair in pairShowers) {
			AdmobLoader.interPool.clear()
			AdmobLoader.videoPool.clear()
			AdConfig.showMaxTime = 0L
			val callback = RecordingCallback()

			assertEquals(AdShowStatus.TIMEOUT, showPair(Activity(), callback))
			assertEquals(listOf(ShowFailResult.LOAD_TIMEOUT), callback.failures)
			assertFalse(AppStatus.isShowingAd)
		}
	}

	private data class PendingLoads(
		val open: CompletableDeferred<AdmobLoader.OpenLoadResult>,
		val inter: CompletableDeferred<AdmobLoader.InterLoadResult>,
		val video: CompletableDeferred<AdmobLoader.VideoLoadResult>,
	)

	private data class CapturedRequest(val id: String, val ad: Any, val succeed: () -> Unit)

	private fun captureAdRequests(): MutableList<CapturedRequest> {
		val requests = mutableListOf<CapturedRequest>()
		replace(AdmobLoader::requestOpenAd, { id, callback ->
			val ad = FakeOpen(id)
			requests += CapturedRequest(id, ad) { callback.onAdLoaded(ad) }
		})
		replace(AdmobLoader::requestInterAd, { id, callback ->
			val ad = FakeInter(id)
			requests += CapturedRequest(id, ad) { callback.onAdLoaded(ad) }
		})
		replace(AdmobLoader::requestVideoAd, { id, callback ->
			val ad = FakeVideo(id)
			requests += CapturedRequest(id, ad) { callback.onAdLoaded(ad) }
		})
		return requests
	}

	private fun configProperty(format: AdFormat) = when (format) {
		AdFormat.OPEN -> AdmobConfig::openConfig
		AdFormat.INTER -> AdmobConfig::interConfig
		else -> AdmobConfig::videoConfig
	}

	private fun poolAds(format: AdFormat): Set<Any> = when (format) {
		AdFormat.OPEN -> AdmobLoader.openPool.keys
		AdFormat.INTER -> AdmobLoader.interPool.keys
		else -> AdmobLoader.videoPool.keys
	}

	private fun isLoading(format: AdFormat) = when (format) {
		AdFormat.OPEN -> AdmobLoader.isLoadingOpen
		AdFormat.INTER -> AdmobLoader.isLoadingInter
		else -> AdmobLoader.isLoadingVideo
	}

	private fun loadedAd(result: Any): Any = when (result) {
		is AdmobLoader.OpenLoadResult.Loaded -> result.ad
		is AdmobLoader.InterLoadResult.Loaded -> result.ad
		is AdmobLoader.VideoLoadResult.Loaded -> result.ad
		else -> error("Expected a successfully loaded ad, got $result")
	}

	private fun installPendingLoads(): PendingLoads {
		val pending = PendingLoads(CompletableDeferred(), CompletableDeferred(), CompletableDeferred())
		loadField("openLoadDeferred").set(null, pending.open)
		loadField("interLoadDeferred").set(null, pending.inter)
		loadField("videoLoadDeferred").set(null, pending.video)
		loadField("openLoadingAdUnitId").set(null, AdmobConfig.openID)
		loadField("interLoadingAdUnitId").set(null, AdmobConfig.interID)
		loadField("videoLoadingAdUnitId").set(null, AdmobConfig.videoID)
		AdmobLoader.isLoadingOpen = true
		AdmobLoader.isLoadingInter = true
		AdmobLoader.isLoadingVideo = true
		return pending
	}

	private fun loadField(name: String) = AdmobLoader::class.java.getDeclaredField(name)
		.apply { isAccessible = true }

	private fun adUnitJson(config: AdUnitConfig) = JSONObject()
		.put("id", config.id)
		.put("timeout", config.timeout)
		.put("poolSize", config.poolSize)
		.put("probeConfig", JSONObject()
			.put("mod", config.probeConfig.mod.name)
			.put("timeout", config.probeConfig.timeout)
			.put("currency", config.probeConfig.currency)
			.put("instances", JSONArray(config.probeConfig.instances.map { instance ->
				JSONObject().put("instanceId", instance.instanceId).put("label", instance.label)
					.put("ecpm", instance.ecpm).put("param", instance.param)
			})))

	private fun probeConfig(mode: ProbeMod) = ProbeConfig(mode, 3_000, "USD", emptyList())

	private fun bounds(mode: ProbeMod, high: Long?, low: Long?) = AdmobAdapterProbeResult(
		AdmobAdapterProbeResult.Status.BOUNDED, probeConfig(mode), hPrice = high, lPrice = low,
	)

	private fun cache(ad: FakeOpen, price: Long?) {
		AdmobLoader.openPool[ad] = System.currentTimeMillis()
		replace(ad::reflectPrice, price?.let { AdmobPrice(it, "USD", 1) })
		replace(ad::adapterProbeResult, AdmobAdapterProxyReader.read(ad.responseInfo, AdmobConfig.openConfig.probeConfig))
	}

	private fun cache(ad: FakeInter, price: Long?) {
		AdmobLoader.interPool[ad] = System.currentTimeMillis()
		replace(ad::reflectPrice, price?.let { AdmobPrice(it, "USD", 1) })
		replace(ad::adapterProbeResult, AdmobAdapterProxyReader.read(ad.responseInfo, AdmobConfig.interConfig.probeConfig))
	}

	private fun cache(ad: FakeVideo, price: Long?) {
		AdmobLoader.videoPool[ad] = System.currentTimeMillis()
		replace(ad::reflectPrice, price?.let { AdmobPrice(it, "USD", 1) })
		replace(ad::adapterProbeResult, AdmobAdapterProxyReader.read(ad.responseInfo, AdmobConfig.videoConfig.probeConfig))
	}

	private fun <T> replace(property: KMutableProperty0<T>, value: T) {
		val previous = property.get()
		restore += { property.set(previous) }
		property.set(value)
	}

	private fun <K, V> clearForTest(map: MutableMap<K, V>) {
		val previous = map.toMap()
		restore += { map.clear(); map.putAll(previous) }
		map.clear()
	}

	private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

	private class RecordingCallback(format: AdFormat = AdFormat.INTER_VIDEO) : ShowCallback {
		override val adContext = ScreenAdContext(
			adFormat = format,
			adPlatform = AdPlatform.ADMOB,
			trigger = ScreenAdTrigger.ENTER,
		)
		val failures = mutableListOf<ShowFailResult>()
		var successes = 0
		var closes = 0
		var rewards = 0
		override fun showFailed(reason: ShowFailResult) { failures += reason }
		override fun showSuccess() { successes++ }
		override fun onClicked() = Unit
		override fun onAdClosed() { closes++ }
		override fun onPaid() = Unit
		override fun onReward() { rewards++ }
	}

	private class FakeOpen(private val unitId: String = "fake-open") : AppOpenAd() {
		var shows = 0
		private var content: FullScreenContentCallback? = null
		private var paid: OnPaidEventListener? = null
		override fun getAdUnitId() = unitId
		override fun show(activity: Activity) { shows++ }
		override fun getResponseInfo(): ResponseInfo = emptyResponseInfo()
		override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
		override fun getFullScreenContentCallback() = content
		override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
		override fun getOnPaidEventListener() = paid
		override fun setImmersiveMode(immersive: Boolean) = Unit
		override fun getPlacementId() = 0L
		override fun setPlacementId(placementId: Long) = Unit
	}

	private class FakeInter(private val unitId: String = "fake-inter") : InterstitialAd() {
		var shows = 0
		var onShow: () -> Unit = {}
		private var content: FullScreenContentCallback? = null
		private var paid: OnPaidEventListener? = null
		private var placement = 0L
		override fun getAdUnitId() = unitId
		override fun show(activity: Activity) { shows++; onShow() }
		override fun getResponseInfo(): ResponseInfo = emptyResponseInfo()
		override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
		override fun getFullScreenContentCallback() = content
		override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
		override fun getOnPaidEventListener() = paid
		override fun setImmersiveMode(immersive: Boolean) = Unit
		override fun getPlacementId() = placement
		override fun setPlacementId(placementId: Long) { placement = placementId }
	}

	private class FakeVideo(private val unitId: String = "fake-video") : RewardedAd() {
		var shows = 0
		var onShow: () -> Unit = {}
		var rewardListener: OnUserEarnedRewardListener? = null
		private var content: FullScreenContentCallback? = null
		private var paid: OnPaidEventListener? = null
		private var metadata: OnAdMetadataChangedListener? = null
		private var placement = 0L
		override fun getAdUnitId() = unitId
		override fun show(activity: Activity, listener: OnUserEarnedRewardListener) {
			shows++
			rewardListener = listener
			onShow()
		}
		override fun getResponseInfo(): ResponseInfo = emptyResponseInfo()
		override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) { content = callback }
		override fun getFullScreenContentCallback() = content
		override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paid = listener }
		override fun getOnPaidEventListener() = paid
		override fun setImmersiveMode(immersive: Boolean) = Unit
		override fun getRewardItem(): RewardItem = RewardItem.DEFAULT_REWARD
		override fun getAdMetadata() = Bundle()
		override fun setOnAdMetadataChangedListener(listener: OnAdMetadataChangedListener?) { metadata = listener }
		override fun getOnAdMetadataChangedListener() = metadata
		override fun setServerSideVerificationOptions(options: ServerSideVerificationOptions?) = Unit
		override fun getPlacementId() = placement
		override fun setPlacementId(placementId: Long) { placement = placementId }
	}

	private companion object {
		// SDK 25.3.0 的构造器允许响应 Binder 为 null，此时适配器数据为空。
		fun emptyResponseInfo(): ResponseInfo = ResponseInfo::class.java.declaredConstructors.single()
			.apply { isAccessible = true }.newInstance(null as Any?) as ResponseInfo
	}
}

package com.mar2sdk.core.ad.impl.admob

import android.app.Activity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real shower/loader wiring with fake ads; never requests a network ad. */
@RunWith(AndroidJUnit4::class)
class AdmobInterVideoShowerTest {

	private val restore = mutableListOf<() -> Unit>()
	private val pairShowers: List<suspend (Activity, ShowCallback) -> AdShowStatus> = listOf(
		AdmobShower::showInterVideo,
		AdmobShower::showVideoInter,
	)

	@Before
	fun setUp() = onMain {
		// appMod may not have been initialized by another test; preserve even its null backing value.
		val appModField = Core::class.java.getDeclaredField("appMod").apply { isAccessible = true }
		val previousAppMod = appModField.get(null)
		restore += { appModField.set(null, previousAppMod) }
		Core.appMod = AppMod.RELEASE
		replace(AppStatus::isShowingAd, false)
		replace(AdConfig::showMinTime, 0L)
		replace(AdConfig::showMaxTime, 1_000L)
		replace(AdmobConfig::interConfig, AdmobConfig.interConfig.copy(poolSize = 0))
		replace(AdmobConfig::videoConfig, AdmobConfig.videoConfig.copy(poolSize = 0))
		replace(LogConfig::fbEvents, emptyList())
		replace(LogConfig::thEvents, emptyList())
		replace(LogConfig::localEvents, emptyList())
		replace(LogConfig::netEvents, emptyList())
		clearForTest(AdmobLoader.interPool)
		clearForTest(AdmobLoader.videoPool)
		for (name in listOf("interLoadDeferred", "videoLoadDeferred")) {
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
	fun sameAdUnitInstancesKeepIndependentPrices() = onMain {
		val first = FakeInter("same-inter")
		val second = FakeInter("same-inter")
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
		val inter = FakeInter("same-inter")
		val secondInter = FakeInter("same-inter")
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
			high.set(40_000_000)
			assertNull(snapshot.get())
			assertEquals(20_000_000L, low.get())
			snapshot.set(result.copy(status = AdmobAdapterProbeResult.Status.UPPER_BOUND_ONLY, lPrice = null))
			assertEquals(30_000_000L, high.get())
			assertNull(low.get())
			low.set(10_000_000)
			assertNull(snapshot.get())
			assertEquals(30_000_000L, high.get())
			snapshot.set(null)
			assertNull(high.get())
			assertNull(low.get())
		}
	}

	@Test
	fun emptySdkResponseDoesNotProduceProbePrices() {
		val config = AdmobConfig.interConfig.probeConfig.copy(instances = listOf(
			AdmobConfig.ProbeInstance("probe", "Probe_30", 30.0, ""),
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
			val lowerInter = FakeInter("lower-inter")
			val inter = FakeInter("winning-inter")
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
			assertEquals("winning-inter", callback.adContext.adUnitId)
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
		val lowerVideo = FakeVideo("lower-video")
		val winningVideo = FakeVideo("winning-video")
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
		assertEquals("winning-video", callback.adContext.adUnitId)
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
			val loadField = AdmobLoader::class.java.getDeclaredField(
				if (missingVideo) "videoLoadDeferred" else "interLoadDeferred"
			).apply { isAccessible = true }
			loadField.set(null, if (missingVideo) pendingVideo else pendingInter)
			val callback = RecordingCallback(AdFormat.VIDEO_INTER)
			val waiting = async(start = CoroutineStart.UNDISPATCHED) {
				AdmobShower.showVideoInter(Activity(), callback)
			}
			try {
				yield()
				assertFalse("Must wait for missing format; missingVideo=$missingVideo", waiting.isCompleted)
				assertTrue(AppStatus.isShowingAd)
				// Mirror the loader clearing its in-flight handle before publishing the loaded ad.
				loadField.set(null, null)
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
				loadField.set(null, null)
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

	private fun cache(ad: FakeInter, price: Long?) {
		AdmobLoader.interPool[ad] = System.currentTimeMillis()
		replace(ad::reflectPrice, price?.let { AdmobPrice(it, "USD", 1) })
	}

	private fun cache(ad: FakeVideo, price: Long?) {
		AdmobLoader.videoPool[ad] = System.currentTimeMillis()
		replace(ad::reflectPrice, price?.let { AdmobPrice(it, "USD", 1) })
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

	private class FakeOpen : AppOpenAd() {
		override fun getAdUnitId() = "fake-open"
		override fun show(activity: Activity) = Unit
		override fun getResponseInfo(): ResponseInfo = emptyResponseInfo()
		override fun setFullScreenContentCallback(callback: FullScreenContentCallback?) = Unit
		override fun getFullScreenContentCallback(): FullScreenContentCallback? = null
		override fun setOnPaidEventListener(listener: OnPaidEventListener?) = Unit
		override fun getOnPaidEventListener(): OnPaidEventListener? = null
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
		// SDK 25.3.0's constructor accepts a null response binder and exposes empty adapter data.
		fun emptyResponseInfo(): ResponseInfo = ResponseInfo::class.java.declaredConstructors.single()
			.apply { isAccessible = true }.newInstance(null as Any?) as ResponseInfo
	}
}

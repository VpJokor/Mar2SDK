package com.mar2sdk.core.ad.impl.admob

import android.app.Activity
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MediaContent
import com.google.android.gms.ads.MuteThisAdListener
import com.google.android.gms.ads.MuteThisAdReason
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.nativead.NativeAd
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.AdShower
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.policy.ScreenAdTrigger
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.LogConfig
import kotlin.reflect.KMutableProperty0
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises native loading and ownership using SDK callbacks without network requests. */
@RunWith(AndroidJUnit4::class)
class AdmobNativeShowerTest {

	private val restore = mutableListOf<() -> Unit>()
	private val requests = mutableListOf<Request>()
	private lateinit var activity: FakeActivity

	@Before
	fun setUp() = onMain {
		val appModField = Core::class.java.getDeclaredField("appMod").apply { isAccessible = true }
		val previousAppMod = appModField.get(null)
		restore += { appModField.set(null, previousAppMod) }
		Core.appMod = AppMod.RELEASE
		activity = FakeActivity()
		replace(AppStatus::isShowingAd, false)
		replace(AdmobConfig::nativeConfig, NativeConfig(5_000L,
			listOf(NativeAdConfig("high", "medium", "low"))))
		replace(LogConfig::fbEvents, emptyList())
		replace(LogConfig::thEvents, emptyList())
		replace(LogConfig::localEvents, emptyList())
		replace(LogConfig::netEvents, emptyList())
		replace(SingularConfig::trackRevenue, false)
		replace(AdmobShower::requestNativeAd, { owner, id, listener, loaded ->
			assertSame(activity, owner)
			requests += Request(id, listener, loaded)
		})
	}

	@After
	fun tearDown() = onMain {
		restore.asReversed().forEach { it() }
		restore.clear()
		requests.clear()
	}

	@Test
	fun facadeFallsBackInOrderAndStopsAtFirstLoadedAd() = runBlocking(Dispatchers.Main) {
		val callback = RecordingCallback()
		val originalContext = callback.adContext.copy()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdShower.getNative(activity, callback)
		}
		try {
			assertEquals(listOf("high"), requests.map { it.id })
			requests.single().fail()
			yield()
			assertEquals(listOf("high", "medium"), requests.map { it.id })
			assertTrue(callback.failures.isEmpty())
			val ad = FakeNative()
			requests.last().loaded(ad)

			assertSame(ad, waiting.await())
			assertEquals(listOf("high", "medium"), requests.map { it.id })
			assertEquals(originalContext.copy(adFormat = AdFormat.NATIVE,
				adPlatform = AdPlatform.ADMOB, adUnitId = "medium"), callback.adContext)
			assertEquals(0, ad.destroys)
			assertEquals(0, callback.successes)
			assertTrue(callback.failures.isEmpty())
			assertFalse(AppStatus.isShowingAd)
		} finally {
			waiting.cancelAndJoin()
		}
	}

	@Test
	fun selectedGroupSkipsBlankIdsAndDebugUsesOfficialTestUnit() = runBlocking(Dispatchers.Main) {
		AdmobConfig.nativeConfig = NativeConfig(5_000L, listOf(
			NativeAdConfig("first-high", "first-medium", "first-low"),
			NativeAdConfig(" ", "second-medium", ""),
		))
		val ad = FakeNative()
		AdmobShower.requestNativeAd = { _, id, listener, loaded ->
			requests += Request(id, listener, loaded)
			loaded(ad)
		}

		assertSame(ad, AdmobShower.getNative(activity, RecordingCallback(), adIndex = 1))
		assertEquals(listOf("second-medium"), requests.map { it.id })
		assertEquals(listOf("second-medium"), AdmobConfig.nativeIDs(1))
		AdmobConfig.nativeConfig = NativeConfig(5_000L, emptyList())
		for (mode in listOf(AppMod.DEBUG, AppMod.TEST)) {
			Core.appMod = mode
			val callback = RecordingCallback()
			assertSame(ad, AdmobShower.getNative(activity, callback))
			assertEquals("ca-app-pub-3940256099942544/2247696110", requests.last().id)
			assertEquals(AdmobConfig.testNativeID, callback.adContext.adUnitId)
			assertTrue(AdmobConfig.nativeIDs(-1).isEmpty())
		}
	}

	@Test
	fun missingGroupsAndBlankIdsFailWithoutStartingRequests() = runBlocking(Dispatchers.Main) {
		for (index in listOf(-1, 1)) {
			val callback = RecordingCallback()
			assertNull(AdmobShower.getNative(activity, callback, adIndex = index))
			assertEquals(listOf(ShowFailResult.LOAD_AD_EXCEPTION), callback.failures)
		}
		for (groups in listOf(emptyList(), listOf(NativeAdConfig("", " ", "")))) {
			AdmobConfig.nativeConfig = NativeConfig(5_000L, groups)
			val callback = RecordingCallback()
			assertNull(AdmobShower.getNative(activity, callback))
			assertEquals(listOf(ShowFailResult.LOAD_AD_EXCEPTION), callback.failures)
		}
		assertTrue(requests.isEmpty())
	}

	@Test
	fun sdkErrorsAndExceptionsFallThroughAndReportOnlyFinalFailure() = runBlocking(Dispatchers.Main) {
		for (lastThrows in listOf(false, true)) {
			requests.clear()
			val callback = RecordingCallback()
			AdmobShower.requestNativeAd = { _, id, listener, loaded ->
				requests += Request(id, listener, loaded)
				if (id == "high" || (id == "low" && lastThrows)) {
					throw IllegalStateException("Simulated SDK exception")
				}
				listener.onAdFailedToLoad(noFill())
			}

			assertNull(AdmobShower.getNative(activity, callback))

			assertEquals(listOf("high", "medium", "low"), requests.map { it.id })
			assertEquals(listOf(if (lastThrows) ShowFailResult.LOAD_AD_EXCEPTION
				else ShowFailResult.LOAD_FAILED), callback.failures)
			assertEquals("low", callback.adContext.adUnitId)
			assertEquals(0, callback.successes)
		}
	}

	@Test
	fun impressionsClicksClosesAndRevenueDoNotChangeFullscreenLock() = runBlocking(Dispatchers.Main) {
		for (fullscreenShowing in listOf(false, true)) {
			AppStatus.isShowingAd = fullscreenShowing
			val callback = RecordingCallback()
			val ad = FakeNative()
			AdmobShower.requestNativeAd = { _, id, listener, loaded ->
				requests += Request(id, listener, loaded)
				loaded(ad)
			}

			assertSame(ad, AdmobShower.getNative(activity, callback))
			val listener = requests.last().listener
			listener.onAdLoaded()
			listener.onAdOpened()
			assertEquals(0, callback.successes)
			listener.onAdImpression()
			listener.onAdClicked()
			ad.paidListener!!.onPaidEvent(paidValue())
			listener.onAdClosed()

			assertEquals(1, callback.successes)
			assertEquals(1, callback.clicks)
			assertEquals(1, callback.closes)
			assertEquals(1, callback.paid)
			assertEquals(0, callback.rewards)
			assertTrue(callback.failures.isEmpty())
			assertEquals(fullscreenShowing, AppStatus.isShowingAd)
		}
	}

	@Test
	fun timeoutDestroysLateAdAndIgnoresItsEvents() = runBlocking(Dispatchers.Main) {
		AdmobConfig.nativeConfig = AdmobConfig.nativeConfig.copy(timeout = 100L)
		val callback = RecordingCallback()

		assertNull(AdmobShower.getNative(activity, callback))

		assertEquals(listOf(ShowFailResult.LOAD_TIMEOUT), callback.failures)
		val request = requests.single()
		val lateAd = FakeNative()
		request.loaded(lateAd)
		request.fail()
		request.listener.onAdImpression()
		request.listener.onAdClicked()
		request.listener.onAdClosed()
		assertEquals(1, lateAd.destroys)
		assertEquals(listOf(ShowFailResult.LOAD_TIMEOUT), callback.failures)
		assertEquals(0, callback.successes)
		assertEquals(0, callback.clicks)
		assertEquals(0, callback.closes)
	}

	@Test
	fun allAttemptsShareOneTimeoutBudget() = runBlocking(Dispatchers.Main) {
		AdmobConfig.nativeConfig = AdmobConfig.nativeConfig.copy(timeout = 800L)
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		try {
			delay(500L)
			requests.single().fail()
			yield()
			assertEquals(listOf("high", "medium"), requests.map { it.id })
			// A fresh 800 ms timeout for the second attempt would exceed this deadline.
			assertNull(withTimeout(600L) { waiting.await() })
			assertEquals(listOf(ShowFailResult.LOAD_TIMEOUT), callback.failures)
		} finally {
			waiting.cancelAndJoin()
		}
	}

	@Test
	fun cancellationPropagatesAndDestroysLateAdWithoutFailureCallback() = runBlocking(Dispatchers.Main) {
		AppStatus.isShowingAd = true
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		val request = requests.single()

		waiting.cancelAndJoin()
		val lateAd = FakeNative()
		request.loaded(lateAd)
		request.fail()
		request.listener.onAdImpression()

		assertTrue(waiting.isCancelled)
		assertEquals(1, lateAd.destroys)
		assertTrue(callback.failures.isEmpty())
		assertEquals(0, callback.successes)
		assertEquals(1, requests.size)
		assertTrue(AppStatus.isShowingAd)
	}

	@Test
	fun cancellationAfterSdkSuccessBeforeDispatchDestroysUnreturnedAd() = runBlocking(Dispatchers.Main) {
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		val ad = FakeNative()
		requests.single().loaded(ad)
		waiting.cancelAndJoin()

		assertTrue(waiting.isCancelled)
		assertEquals(1, ad.destroys)
		assertTrue(callback.failures.isEmpty())
	}

	@Test
	fun failedAttemptCannotDeliverLateAdOrEventsAfterFallbackSucceeds() = runBlocking(Dispatchers.Main) {
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		try {
			val first = requests.single()
			first.fail()
			yield()
			val selected = FakeNative()
			requests.last().loaded(selected)
			assertSame(selected, waiting.await())

			val lateAd = FakeNative()
			first.loaded(lateAd)
			first.listener.onAdImpression()
			first.listener.onAdClicked()
			first.listener.onAdClosed()
			assertEquals(1, lateAd.destroys)
			assertEquals(0, selected.destroys)
			assertEquals(0, callback.successes)
			assertEquals(0, callback.clicks)
			assertEquals(0, callback.closes)
			assertTrue(callback.failures.isEmpty())
		} finally {
			waiting.cancelAndJoin()
		}
	}

	@Test
	fun inactiveActivitiesFailWithoutRequestsAndLoadedAdsAreDestroyedIfActivityFinishes() = runBlocking(Dispatchers.Main) {
		for (destroyed in listOf(false, true)) {
			activity.finishing = !destroyed
			activity.destroyed = destroyed
			val callback = RecordingCallback()
			assertNull(AdmobShower.getNative(activity, callback))
			assertEquals(listOf(ShowFailResult.ACTIVITY_IS_FINISHING), callback.failures)
			assertTrue(requests.isEmpty())
		}
		activity.finishing = false
		activity.destroyed = false
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		try {
			activity.finishing = true
			val ad = FakeNative()
			requests.single().loaded(ad)

			assertNull(waiting.await())
			assertEquals(1, ad.destroys)
			assertEquals(listOf(ShowFailResult.ACTIVITY_IS_FINISHING), callback.failures)
			assertEquals(0, callback.successes)
		} finally {
			waiting.cancelAndJoin()
		}
	}

	@Test
	fun activityFinishingAfterFailureStopsTheWaterfall() = runBlocking(Dispatchers.Main) {
		val callback = RecordingCallback()
		val waiting = async(start = CoroutineStart.UNDISPATCHED) {
			AdmobShower.getNative(activity, callback)
		}
		try {
			activity.finishing = true
			requests.single().fail()

			assertNull(waiting.await())
			assertEquals(listOf("high"), requests.map { it.id })
			assertEquals(listOf(ShowFailResult.ACTIVITY_IS_FINISHING), callback.failures)
		} finally {
			waiting.cancelAndJoin()
		}
	}

	private fun <T> replace(property: KMutableProperty0<T>, value: T) {
		val previous = property.get()
		restore += { property.set(previous) }
		property.set(value)
	}

	private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

	private class Request(val id: String, val listener: AdListener, val loaded: (NativeAd) -> Unit) {
		fun fail() = listener.onAdFailedToLoad(noFill())
	}

	private class FakeActivity : Activity() {
		var finishing = false
		var destroyed = false
		override fun isFinishing() = finishing
		override fun isDestroyed() = destroyed
	}

	private class RecordingCallback : ShowCallback {
		override val adContext = ScreenAdContext(
			areaKey = "home_native",
			adFormat = AdFormat.INTER,
			adPlatform = AdPlatform.MAX,
			trigger = ScreenAdTrigger.ENTER,
			fromRoute = "launch",
			toRoute = "home",
			adUnitId = "stale-unit",
		)
		val failures = mutableListOf<ShowFailResult>()
		var successes = 0
		var clicks = 0
		var closes = 0
		var paid = 0
		var rewards = 0
		override fun showFailed(reason: ShowFailResult) { failures += reason }
		override fun showSuccess() { successes++ }
		override fun onClicked() { clicks++ }
		override fun onAdClosed() { closes++ }
		override fun onPaid() { paid++ }
		override fun onReward() { rewards++ }
	}

	@Suppress("OVERRIDE_DEPRECATION")
	private class FakeNative : NativeAd() {
		var destroys = 0
		var paidListener: OnPaidEventListener? = null
		private var placement = 0L
		override fun destroy() { destroys++ }
		override fun getHeadline() = "Test ad"
		override fun getImages(): List<Image> = emptyList()
		override fun getBody(): String? = null
		override fun getIcon(): Image? = null
		override fun getCallToAction(): String? = null
		override fun getAdvertiser(): String? = null
		override fun getStarRating(): Double? = null
		override fun getStore(): String? = null
		override fun getPrice(): String? = null
		override fun getAdChoicesInfo(): AdChoicesInfo? = null
		override fun isCustomMuteThisAdEnabled() = false
		override fun getMuteThisAdReasons(): List<MuteThisAdReason> = emptyList()
		override fun muteThisAd(reason: MuteThisAdReason) = Unit
		override fun setMuteThisAdListener(listener: MuteThisAdListener) = Unit
		override fun getExtras() = Bundle()
		override fun setUnconfirmedClickListener(listener: UnconfirmedClickListener) = Unit
		override fun cancelUnconfirmedClick() = Unit
		override fun enableCustomClickGesture() = Unit
		override fun isCustomClickGestureEnabled() = false
		override fun recordCustomClickGesture() = Unit
		override fun performClick(bundle: Bundle) = Unit
		override fun recordImpression(bundle: Bundle) = false
		override fun reportTouchEvent(bundle: Bundle) = Unit
		override fun getMediaContent(): MediaContent? = null
		override fun getResponseInfo(): ResponseInfo? = null
		override fun setOnPaidEventListener(listener: OnPaidEventListener?) { paidListener = listener }
		override fun recordEvent(bundle: Bundle) = Unit
		override fun getPlacementId() = placement
		override fun setPlacementId(placementId: Long) { placement = placementId }
		override fun zza(): Any? = null
	}

	private companion object {
		fun noFill() = LoadAdError(3, "No fill", "test", null, null)

		fun paidValue(): AdValue = AdValue::class.java.getDeclaredConstructor(
			Int::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
		).apply { isAccessible = true }.newInstance(1, "USD", 12_000L)
	}
}

package com.mar2sdk.core.ad.impl.admob

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
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

/** 使用真实 AdView 和模拟 SDK 回调，不发送广告加载请求。 */
@RunWith(AndroidJUnit4::class)
class AdmobBannerShowerTest {

	private val restore = mutableListOf<() -> Unit>()
	private val requests = mutableListOf<AdView>()
	private lateinit var scenario: ActivityScenario<BannerTestActivity>
	private lateinit var activity: BannerTestActivity

	@Before
	fun setUp() {
		onMain {
			val appModField = Core::class.java.getDeclaredField("appMod").apply { isAccessible = true }
			val previousAppMod = appModField.get(null)
			restore += { appModField.set(null, previousAppMod) }
			Core.appMod = AppMod.RELEASE
			replace(AppStatus::isShowingAd, false)
			replace(AdmobConfig::bannerConfig, BannerConfig("configured-banner"))
			replace(LogConfig::fbEvents, emptyList())
			replace(LogConfig::thEvents, emptyList())
			replace(LogConfig::localEvents, emptyList())
			replace(LogConfig::netEvents, emptyList())
			replace(SingularConfig::trackRevenue, false)
			replace(AdmobShower::requestBannerAd, { requests += it })
		}
		val context = InstrumentationRegistry.getInstrumentation().context
		scenario = ActivityScenario.launch(Intent(context, BannerTestActivity::class.java))
		scenario.onActivity { activity = it }
	}

	@After
	fun tearDown() {
		try {
			onMain { requests.forEach { it.destroy() } }
		} finally {
			try {
				if (::scenario.isInitialized) scenario.close()
			} finally {
				onMain {
					restore.asReversed().forEach { it() }
					restore.clear()
					requests.clear()
				}
			}
		}
	}

	@Test
	fun facadeReturnsConfiguredViewImmediatelyAndNormalizesContext() = onMain {
		val callback = RecordingCallback()
		val originalContext = callback.adContext.copy()

		val view = AdShower.getBanner(activity, callback)

		assertNotNull(view)
		assertSame(view, requests.single())
		assertSame(activity, view!!.context)
		assertEquals(AdSize.BANNER, view.adSize)
		assertEquals("configured-banner", view.adUnitId)
		assertEquals(originalContext.copy(adFormat = AdFormat.BANNER,
			adPlatform = AdPlatform.ADMOB, adUnitId = "configured-banner"), callback.adContext)
		assertNotNull(view.adListener)
		assertNotNull(view.onPaidEventListener)
		assertNull(view.parent)
		assertEquals(0, callback.successes)
		assertTrue(callback.failures.isEmpty())
		assertFalse(AppStatus.isShowingAd)
	}

	@Test
	fun customSizeAndOfficialTestUnitAreUsedInDebugAndTestModes() = onMain {
		for (mode in listOf(AppMod.DEBUG, AppMod.TEST)) {
			Core.appMod = mode
			val callback = RecordingCallback()

			val view = AdShower.getBanner(activity, callback, AdSize.LARGE_BANNER)

			assertSame(view, requests.last())
			assertEquals(AdSize.LARGE_BANNER, view!!.adSize)
			assertEquals(AdmobConfig.testBannerID, view.adUnitId)
			assertEquals(AdmobConfig.testBannerID, callback.adContext.adUnitId)
		}
	}

	@Test
	fun onlyImpressionsReportSuccessAndNeverChangeTheFullscreenLock() = onMain {
		for (fullscreenShowing in listOf(false, true)) {
			AppStatus.isShowingAd = fullscreenShowing
			val callback = RecordingCallback()
			val view = AdmobShower.getBanner(activity, callback)!!

			view.adListener.onAdLoaded()
			view.adListener.onAdOpened()
			assertEquals(0, callback.successes)
			assertEquals(fullscreenShowing, AppStatus.isShowingAd)

			view.adListener.onAdImpression()
			assertEquals(1, callback.successes)
			assertEquals(fullscreenShowing, AppStatus.isShowingAd)
			view.adListener.onAdClosed()
			assertEquals(1, callback.closes)
			assertEquals(fullscreenShowing, AppStatus.isShowingAd)
			assertTrue(callback.failures.isEmpty())
		}
	}

	@Test
	fun refreshCallbacksContinueForImpressionsClicksClosesAndRevenue() = onMain {
		AppStatus.isShowingAd = true
		val callback = RecordingCallback()
		val view = AdmobShower.getBanner(activity, callback)!!
		val paidValue = AdValue::class.java.getDeclaredConstructor(
			Int::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
		).apply { isAccessible = true }.newInstance(1, "USD", 12_000L)

		repeat(2) {
			view.adListener.onAdLoaded()
			view.adListener.onAdImpression()
			view.adListener.onAdClicked()
			view.onPaidEventListener!!.onPaidEvent(paidValue)
			view.adListener.onAdClosed()
		}

		assertEquals(2, callback.successes)
		assertEquals(2, callback.clicks)
		assertEquals(2, callback.closes)
		assertEquals(2, callback.paid)
		assertEquals(0, callback.rewards)
		assertTrue(callback.failures.isEmpty())
		assertTrue(AppStatus.isShowingAd)
	}

	@Test
	fun failedRefreshReportsLoadFailureAndLaterRefreshCanSucceed() = onMain {
		AppStatus.isShowingAd = true
		val callback = RecordingCallback()
		val view = AdmobShower.getBanner(activity, callback)!!
		val loadError = LoadAdError(3, "No fill", "test", null, null)

		view.adListener.onAdFailedToLoad(loadError)
		view.adListener.onAdLoaded()
		assertEquals(0, callback.successes)
		view.adListener.onAdImpression()
		view.adListener.onAdFailedToLoad(loadError)
		view.adListener.onAdLoaded()
		view.adListener.onAdImpression()

		assertEquals(listOf(ShowFailResult.LOAD_FAILED, ShowFailResult.LOAD_FAILED), callback.failures)
		assertEquals(2, callback.successes)
		assertEquals(1, requests.size)
		assertTrue(AppStatus.isShowingAd)
	}

	@Test
	fun finishingAndDestroyedActivitiesFailWithoutStartingRequests() {
		onMain {
			AppStatus.isShowingAd = true
			activity.finish()
			assertTrue(activity.isFinishing)
			val callback = RecordingCallback()
			assertNull(AdmobShower.getBanner(activity, callback))
			assertEquals(listOf(ShowFailResult.ACTIVITY_IS_FINISHING), callback.failures)
			assertTrue(requests.isEmpty())
			assertTrue(AppStatus.isShowingAd)
		}
		scenario.moveToState(Lifecycle.State.DESTROYED)
		onMain {
			assertTrue(activity.isDestroyed)
			val callback = RecordingCallback()
			assertNull(AdmobShower.getBanner(activity, callback))
			assertEquals(listOf(ShowFailResult.ACTIVITY_IS_FINISHING), callback.failures)
			assertTrue(requests.isEmpty())
			assertTrue(AppStatus.isShowingAd)
		}
	}

	@Test
	fun startupExceptionReturnsNullAndPreservesTheFullscreenLock() = onMain {
		AppStatus.isShowingAd = true
		AdmobShower.requestBannerAd = { view ->
			requests += view
			throw IllegalStateException("Simulated SDK load exception")
		}
		val callback = RecordingCallback()

		assertNull(AdmobShower.getBanner(activity, callback))

		assertEquals(listOf(ShowFailResult.LOAD_AD_EXCEPTION), callback.failures)
		assertEquals(0, callback.successes)
		assertEquals(1, requests.size)
		assertTrue(AppStatus.isShowingAd)
	}

	@Test
	fun backgroundCallsAreRejectedBeforeStartingRequests() {
		val callback = RecordingCallback()

		val result = runCatching { AdShower.getBanner(activity, callback) }

		assertTrue(result.exceptionOrNull() is IllegalStateException)
		assertTrue(requests.isEmpty())
		assertTrue(callback.failures.isEmpty())
		assertEquals(0, callback.successes)
	}

	private fun <T> replace(property: KMutableProperty0<T>, value: T) {
		val previous = property.get()
		restore += { property.set(previous) }
		property.set(value)
	}

	private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

	private class RecordingCallback : ShowCallback {
		override val adContext = ScreenAdContext(
			areaKey = "home_banner",
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
}

class BannerTestActivity : Activity()

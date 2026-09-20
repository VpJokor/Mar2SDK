package com.mar2sdk.core.ad.impl.admob

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.mediation.Adapter
import com.google.android.gms.ads.mediation.InitializationCompleteCallback
import com.google.android.gms.ads.mediation.MediationAdLoadCallback
import com.google.android.gms.ads.mediation.MediationAppOpenAd
import com.google.android.gms.ads.mediation.MediationAppOpenAdCallback
import com.google.android.gms.ads.mediation.MediationAppOpenAdConfiguration
import com.google.android.gms.ads.mediation.MediationInterstitialAd
import com.google.android.gms.ads.mediation.MediationInterstitialAdCallback
import com.google.android.gms.ads.mediation.MediationInterstitialAdConfiguration
import com.google.android.gms.ads.mediation.MediationRewardedAd
import com.google.android.gms.ads.mediation.MediationRewardedAdCallback
import com.google.android.gms.ads.mediation.MediationRewardedAdConfiguration
import com.mar2sdk.core.ad.impl.admob.probe.AdmobProxyAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Calls the bundled GMA adapter contract directly without requesting network ads. */
@RunWith(AndroidJUnit4::class)
class AdmobProxyAdapterTest {

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun publicNoArgAdapterInitializesImmediately() {
		val adapter: Adapter = AdmobProxyAdapter::class.java.getConstructor().newInstance()
		var successes = 0
		val failures = mutableListOf<String>()

		adapter.initialize(context, object : InitializationCompleteCallback {
			override fun onInitializationSucceeded() { successes++ }
			override fun onInitializationFailed(error: String) { failures += error }
		}, emptyList())

		assertEquals(1, successes)
		assertTrue(failures.isEmpty())
		assertEquals("1.0.0", adapter.versionInfo.toString())
		assertEquals("1.0.0", adapter.getSDKVersionInfo().toString())
	}

	@Test
	fun appOpenRequestsEachReceiveOneProbeFailure() {
		val adapter = AdmobProxyAdapter()
		val callbacks = List(2) { RecordingCallback<MediationAppOpenAd, MediationAppOpenAdCallback>() }

		callbacks.forEachIndexed { index, callback ->
			adapter.loadAppOpenAd(MediationAppOpenAdConfiguration(
				context, "", parameters(index), Bundle(), false, null, -1, -1, "", "",
			), callback)
			callback.assertProbeFailure()
		}

		callbacks.forEach { it.assertProbeFailure() }
	}

	@Test
	fun interstitialRequestsEachReceiveOneProbeFailure() {
		val adapter = AdmobProxyAdapter()
		val callbacks = List(2) { RecordingCallback<MediationInterstitialAd, MediationInterstitialAdCallback>() }

		callbacks.forEachIndexed { index, callback ->
			adapter.loadInterstitialAd(MediationInterstitialAdConfiguration(
				context, "", parameters(index), Bundle(), false, null, -1, -1, "", "",
			), callback)
			callback.assertProbeFailure()
		}

		callbacks.forEach { it.assertProbeFailure() }
	}

	@Test
	fun rewardedRequestsEachReceiveOneProbeFailure() {
		val adapter = AdmobProxyAdapter()
		val callbacks = List(2) { RecordingCallback<MediationRewardedAd, MediationRewardedAdCallback>() }

		callbacks.forEachIndexed { index, callback ->
			adapter.loadRewardedAd(MediationRewardedAdConfiguration(
				context, "", parameters(index), Bundle(), false, null, -1, -1, "", "",
			), callback)
			callback.assertProbeFailure()
		}

		callbacks.forEach { it.assertProbeFailure() }
	}

	private fun parameters(index: Int) = Bundle().apply {
		putString("parameter", "probe-$index")
	}

	private class RecordingCallback<Ad : Any, Callback : Any> : MediationAdLoadCallback<Ad, Callback> {
		private var successes = 0
		private val failures = mutableListOf<AdError>()

		override fun onSuccess(ad: Ad): Callback {
			successes++
			throw AssertionError("A price probe must never return an ad")
		}

		override fun onFailure(error: AdError) { failures += error }

		fun assertProbeFailure() {
			assertEquals(0, successes)
			assertEquals(1, failures.size)
			assertEquals(AdmobProxyAdapter.PROBE_NO_FILL_CODE, failures.single().code)
			assertEquals(AdmobProxyAdapter.PROBE_ERROR_DOMAIN, failures.single().domain)
			assertEquals("Intentional probe no fill", failures.single().message)
		}
	}
}

package com.mar2sdk.core.ad

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.common.PreferenceDelegate
import java.util.concurrent.atomic.AtomicBoolean

/** Google UMP consent and privacy options helper. */
object UMPUtil {

	private const val TAG = "UMPUtil"
	private const val REQUEST_TIMEOUT_MILLIS = 6_000L
	private const val TEST_CACHE_MILLIS = 60_000L
	private const val RELEASE_CACHE_MILLIS = 24 * 60 * 60 * 1_000L
	private const val TEST_DEVICE_HASH = "11BF2FA96B84C5054A2D65A532710C0D"

	/** Whether the host should expose a privacy options entry point. */
	@Volatile
	var isPrivacyOptionsRequired: Boolean = false

	private var requestUMPTime: Long by PreferenceDelegate("requestUMPTime", 0L)
	private var requestUMPResult: Boolean by PreferenceDelegate("requestUMPResult", false)

	private val mainHandler = Handler(Looper.getMainLooper())

	private val isTestEnvironment: Boolean
		get() = runCatching {
			Core.appMod == AppMod.DEBUG || Core.appMod == AppMod.TEST
		}.getOrDefault(false)

	/**
	 * Requests an update to the UMP consent state.
	 *
	 * The return value is true when a recent result was cached. Otherwise the request is
	 * asynchronous and [onComplete] receives the final result.
	 */
	@MainThread
	fun initUMP(activity: Activity, onComplete: (success: Boolean) -> Unit): Boolean {
		val cacheTime = if (isTestEnvironment) TEST_CACHE_MILLIS else RELEASE_CACHE_MILLIS
		if (System.currentTimeMillis() - requestUMPTime < cacheTime) {
			isPrivacyOptionsRequired = requestUMPResult
			return true
		}

		val paramsBuilder = ConsentRequestParameters.Builder()
		if (isTestEnvironment) {
			paramsBuilder.setConsentDebugSettings(
				ConsentDebugSettings.Builder(activity)
					.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
					.addTestDeviceHashedId(TEST_DEVICE_HASH)
					.build()
			)
		}

		val consentInformation = try {
			UserMessagingPlatform.getConsentInformation(activity).also {
				if (isTestEnvironment) it.reset()
			}
		} catch (exception: Exception) {
			Log.e(TAG, "Unable to initialize UMP consent information", exception)
			onComplete(false)
			return false
		}

		val completed = AtomicBoolean(false)
		val timeoutRunnable = Runnable {
			if (completed.compareAndSet(false, true)) {
				Log.e(TAG, "requestConsentInfoUpdate timed out")
				onComplete(false)
			}
		}
		mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MILLIS)

		fun complete(success: Boolean) {
			if (!completed.compareAndSet(false, true)) return
			mainHandler.removeCallbacks(timeoutRunnable)
			if (success) {
				isPrivacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus ==
						ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
				requestUMPTime = System.currentTimeMillis()
				requestUMPResult = isPrivacyOptionsRequired
			}
			onComplete(success)
		}

		try {
			consentInformation.requestConsentInfoUpdate(
				activity,
				paramsBuilder.build(),
				{ complete(true) },
				{ error ->
					Log.e(TAG, "requestConsentInfoUpdate failed: ${error.message}")
					complete(false)
				}
			)
		} catch (exception: Exception) {
			Log.e(TAG, "requestConsentInfoUpdate could not be started", exception)
			complete(false)
		}
		return false
	}

	/** Shows the consent form when UMP says one is available and required. */
	@MainThread
	fun showSplashUMP(activity: Activity, onComplete: () -> Unit) {
		val formAvailable = try {
			UserMessagingPlatform.getConsentInformation(activity).isConsentFormAvailable
		} catch (exception: Exception) {
			Log.e(TAG, "Unable to show UMP consent form", exception)
			onComplete()
			return
		}
		if (formAvailable) {
			loadConsentForm(activity, onComplete)
		} else {
			onComplete()
		}
	}

	@MainThread
	private fun loadConsentForm(activity: Activity, onComplete: () -> Unit) {
		val completed = AtomicBoolean(false)
		fun complete() {
			if (completed.compareAndSet(false, true)) onComplete()
		}

		try {
			UserMessagingPlatform.loadConsentForm(
				activity,
				{ consentForm ->
					try {
						val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
						if (consentInformation.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
							consentForm.show(activity) { complete() }
						} else {
							complete()
						}
					} catch (exception: Exception) {
						Log.e(TAG, "Unable to show UMP consent form", exception)
						complete()
					}
				},
				{ error ->
					Log.e(TAG, "loadConsentForm failed: ${error.message}")
					complete()
				}
			)
		} catch (exception: Exception) {
			Log.e(TAG, "Unable to load UMP consent form", exception)
			complete()
		}
	}

	/** Opens the UMP privacy options form. */
	@MainThread
	fun showUMP(activity: Activity) {
		try {
			UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
				if (error != null) {
					Log.e(TAG, "showPrivacyOptionsForm failed: ${error.message}")
				}
			}
		} catch (exception: Exception) {
			Log.e(TAG, "Unable to show UMP privacy options", exception)
		}
	}
}

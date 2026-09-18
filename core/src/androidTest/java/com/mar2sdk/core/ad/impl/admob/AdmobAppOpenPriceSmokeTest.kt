package com.mar2sdk.core.ad.impl.admob

import android.os.Handler
import android.os.IInterface
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPriceEventFields
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPriceField
import com.mar2sdk.core.ad.impl.admob.probe.AdmobReflectProbe
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt in with the instrumentation argument admobAppOpenSmoke=true. */
@RunWith(AndroidJUnit4::class)
class AdmobAppOpenPriceSmokeTest {
	@Test
	fun officialTestAdReportsTheLoadedObjectChainAndPriceSnapshot() {
		assumeTrue(
			"Requires admobAppOpenSmoke=true and a device with network access",
			InstrumentationRegistry.getArguments().getString("admobAppOpenSmoke") == "true"
		)
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val mainHandler = Handler(Looper.getMainLooper())
		val completed = CountDownLatch(1)
		val active = AtomicBoolean(true)
		val loadedAd = AtomicReference<AppOpenAd>()
		val failure = AtomicReference<String>()
		val callback = object : AppOpenAd.AppOpenAdLoadCallback() {
			override fun onAdLoaded(ad: AppOpenAd) {
				loadedAd.set(ad)
				completed.countDown()
			}

			override fun onAdFailedToLoad(error: LoadAdError) {
				failure.set("domain=${error.domain}, code=${error.code}, message=${error.message}")
				completed.countDown()
			}
		}
		mainHandler.post {
			if (active.get()) {
				try {
					MobileAds.initialize(context) {
						mainHandler.post {
							if (active.get()) {
								try {
									AppOpenAd.load(context, TEST_AD_UNIT_ID, AdRequest.Builder().build(), callback)
								} catch (error: Exception) {
									failure.set("AppOpenAd.load: ${error.javaClass.name}: ${error.message}")
									completed.countDown()
								}
							}
						}
					}
				} catch (error: Exception) {
					failure.set("MobileAds.initialize: ${error.javaClass.name}: ${error.message}")
					completed.countDown()
				}
			}
		}
		val finished = try {
			completed.await(60, TimeUnit.SECONDS)
		} finally {
			active.set(false)
		}
		assertTrue("AdMob initialization / test app-open load did not finish within 60 seconds", finished)
		assertEquals("Official app-open test ad failed to load: ${failure.get()}", null, failure.get())
		val ad = loadedAd.get()
		assertNotNull("The load callback did not provide an app-open ad", ad)
		assertEquals(AdmobReflectProbe.openPath.first().runtimeClassName, ad.javaClass.name)

		val local = observePath(ad, AdmobReflectProbe.openPath)
		Log.i(TAG, "localClasses=${local.classes}, localFields=${local.fields}")
		val binder = (local.terminal as? IInterface)?.asBinder()
		var supportedDynamiteModule = false
		binder?.let { localBinder ->
			val binderClass = localBinder.javaClass
			val hierarchy = generateSequence(binderClass as Class<*>?) { it.superclass }
				.map { it.name }.toList()
			val fields = if (binderClass.name == "android.os.BinderProxy") {
				emptyList()
			} else {
				binderClass.declaredFields.take(16).map { "${it.name}:${it.type.name}" }
			}
			Log.i(TAG, "binderClass=${binderClass.name}, binderHierarchy=$hierarchy, binderFields=$fields")
			val descriptor = runCatching {
				Class.forName(MODULE_DESCRIPTOR, false, binderClass.classLoader)
			}.getOrNull()
			val moduleId = runCatching { descriptor?.getDeclaredField("MODULE_ID")?.get(null) as? String }.getOrNull()
			val moduleVersion = runCatching { descriptor?.getDeclaredField("MODULE_VERSION")?.get(null) as? Int }.getOrNull()
			val sameLoader = descriptor != null && descriptor.classLoader === binderClass.classLoader
			Log.i(TAG, "moduleId=$moduleId, moduleVersion=$moduleVersion, moduleAndBinderShareLoader=$sameLoader")
			supportedDynamiteModule = moduleId == "com.google.android.gms.ads.dynamite" &&
				moduleVersion == 260480602 && sameLoader
		}
		val dynamite = if (!local.complete && binder != null) {
			observePath(ad, AdmobReflectProbe.dynamiteOpenPath).also {
				Log.i(TAG, "dynamiteClasses=${it.classes}, dynamiteFields=${it.fields}")
			}
		} else {
			null
		}
		val observation = dynamite ?: local
		val event = observation.terminal
		val eventFields = when (event?.javaClass?.name) {
			AdmobReflectProbe.EVENT_CLASS_NAME -> AdmobPriceEventFields()
			AdmobReflectProbe.DYNAMITE_EVENT_CLASS_NAME -> AdmobReflectProbe.dynamiteEventFields
			else -> null
		}
		fun eventField(name: String?): Any? = if (event == null || name == null) null else {
			runCatching { event.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(event) }.getOrNull()
		}
		val eventType = eventField(eventFields?.eventType) as? Int
		val precision = eventField(eventFields?.precision) as? Int
		val currency = eventField(eventFields?.currency) as? String
		val valueMicros = eventField(eventFields?.valueMicros) as? Long
		val price = AdmobReflectProbe.read(ad)
		Log.i(
			TAG,
			"eventClass=${event?.javaClass?.name}, eventType=$eventType, precision=$precision, " +
				"currency=$currency, valueMicros=$valueMicros, probe=$price"
		)

		// A successful load alone is not a price hit: unsupported module versions, missing
		// events and test ads with zero value legitimately produce null.
		val validPrecision = precision == AdValue.PrecisionType.ESTIMATED ||
			precision == AdValue.PrecisionType.PUBLISHER_PROVIDED ||
			precision == AdValue.PrecisionType.PRECISE
		val supportedPath = observation.complete && (dynamite == null || supportedDynamiteModule)
		val expectedPrice = if (supportedPath && eventType == 3 && validPrecision && currency == "USD" &&
			valueMicros != null && valueMicros > 0 && valueMicros <= Long.MAX_VALUE / 1_000
		) {
			AdmobPrice(valueMicros, currency, requireNotNull(precision))
		} else {
			null
		}
		assertEquals("Probe result must match the actual loaded event, including absence of a usable price", expectedPrice, price)
	}

	private fun observePath(ad: Any, path: List<AdmobPriceField>): PathObservation {
		val classes = mutableListOf<String?>(ad.javaClass.name)
		val fields = mutableListOf<String>()
		var current: Any? = ad
		for (node in path) {
			val target = current
			if (target == null || target.javaClass.name != node.runtimeClassName) {
				return PathObservation(current, false, classes, fields)
			}
			val field = runCatching {
				val declaringClass = generateSequence(target.javaClass as Class<*>?) { it.superclass }
					.first { it.name == node.declaringClassName }
				declaringClass.getDeclaredField(node.fieldName)
			}.getOrNull() ?: return PathObservation(current, false, classes, fields)
			fields.add("${field.declaringClass.name}.${field.name}:${field.type.name}")
			if (field.type.name != node.fieldTypeName || Modifier.isStatic(field.modifiers)) {
				return PathObservation(current, false, classes, fields)
			}
			current = runCatching { field.apply { isAccessible = true }.get(target) }.getOrNull()
			classes.add(current?.javaClass?.name)
		}
		return PathObservation(current, true, classes, fields)
	}

	private data class PathObservation(
		val terminal: Any?,
		val complete: Boolean,
		val classes: List<String?>,
		val fields: List<String>,
	)

	private companion object {
		const val TAG = "AdmobAppOpenSmoke"
		const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"
		const val MODULE_DESCRIPTOR = "com.google.android.gms.dynamite.descriptors.com.google.android.gms.ads.dynamite.ModuleDescriptor"
	}
}

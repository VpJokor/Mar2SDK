package com.mar2sdk.core.ad.impl.admob

import android.util.JsonReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.ads.MobileAds
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPriceExtractor
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPriceProbe
import java.io.StringReader
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdmobPriceProbeInstrumentedTest {

	@Test
	fun bundledSdkMatchesAllSupportedReflectionPaths() {
		assertEquals("25.3.0", MobileAds.getVersion().toString())

		for (path in listOf(
			AdmobPriceProbe.interstitialPath,
			AdmobPriceProbe.rewardedPath,
			AdmobPriceProbe.openPath,
		)) {
			assertFalse("The ad path must reach a revenue event", path.isEmpty())
			for ((index, signature) in path.withIndex()) {
				val runtimeClass = Class.forName(signature.runtimeClassName)
				val declaringClass = Class.forName(signature.declaringClassName)
				assertTrue(signature.toString(), declaringClass.isAssignableFrom(runtimeClass))
				val field = declaringClass.getDeclaredField(signature.fieldName)
				assertEquals(signature.toString(), signature.fieldTypeName, field.type.name)
				assertFalse(signature.toString(), Modifier.isStatic(field.modifiers))
				val nextRuntimeClass = Class.forName(
					path.getOrNull(index + 1)?.runtimeClassName ?: AdmobPriceProbe.EVENT_CLASS_NAME
				)
				assertTrue(
					"${signature.fieldTypeName} must accept ${nextRuntimeClass.name}",
					field.type.isAssignableFrom(nextRuntimeClass)
				)
			}
		}
	}

	@Test
	fun decodesTheBundledSdkRevenueEvent() {
		val eventClass = Class.forName(AdmobPriceProbe.EVENT_CLASS_NAME)
		val constructor = eventClass.getDeclaredConstructor(
			Int::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			String::class.java,
			Long::class.javaPrimitiveType
		)
		constructor.isAccessible = true
		val event = constructor.newInstance(3, 1, "USD", 12_345L)

		val price = AdmobPriceExtractor(emptyList(), eventClass.name).read(event)

		assertEquals(AdmobPrice(12_345L, "USD", 1), price)
	}

	@Test
	fun readsRevenueParsedByTheBundledSdkFromAnAppOpenResponse() {
		val responseNode = AdmobPriceProbe.openPath.last()
		val constructor = Class.forName(responseNode.runtimeClassName)
			.getDeclaredConstructor(JsonReader::class.java)
			.apply { isAccessible = true }
		fun parseResponse(json: String): Any = JsonReader(StringReader(json)).use { reader ->
			constructor.newInstance(reader)
		}
		val extractor = AdmobPriceExtractor(listOf(responseNode), AdmobPriceProbe.EVENT_CLASS_NAME)
		val pricedResponse = parseResponse(
			"""{
				"ad_type": "app_open_ad",
				"ad_event_value": {"type_num": 3, "precision_num": 1, "currency": "USD", "value": 12345}
			}"""
		)

		assertEquals(AdmobPrice(12_345L, "USD", 1), extractor.read(pricedResponse))
		assertNull(extractor.read(parseResponse("""{"ad_type": "app_open_ad"}""")))
	}
}

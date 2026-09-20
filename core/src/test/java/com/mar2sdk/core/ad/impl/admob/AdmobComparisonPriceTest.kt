package com.mar2sdk.core.ad.impl.admob

import com.mar2sdk.core.ad.impl.admob.ProbeMod
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdmobComparisonPriceTest {

	@Test
	fun adapterModesUseUpperMidpointAndLowerPrices() {
		assertEquals(30_000_001L, price(ProbeMod.ADAPTER_H, 30_000_001, 20_000_000))
		assertEquals(25_000_000L, price(ProbeMod.ADAPTER_M, 30_000_001, 20_000_000))
		assertEquals(20_000_000L, price(ProbeMod.ADAPTER_L, 30_000_001, 20_000_000))
		for (mode in adapterModes) {
			assertEquals(mode.name, 7L, price(mode, 7, 7))
		}
	}

	@Test
	fun missingRequiredBoundDoesNotFallBackToAnotherSource() {
		assertEquals(30L, price(ProbeMod.ADAPTER_H, 30, null))
		assertEquals(20L, price(ProbeMod.ADAPTER_L, null, 20))
		assertNull(price(ProbeMod.ADAPTER_H, null, 20))
		assertNull(price(ProbeMod.ADAPTER_L, 30, null))
		assertNull(price(ProbeMod.ADAPTER_M, 30, null))
		assertNull(price(ProbeMod.ADAPTER_M, null, 20))
		for (mode in adapterModes) {
			assertNull(mode.name, price(mode, null, null))
		}
	}

	@Test
	fun invalidBoundInvalidatesEntireAdapterInterval() {
		for (mode in adapterModes) {
			for ((high, low) in listOf(0L to 1L, -1L to 1L, 1L to 0L,
				1L to -1L, 10L to 20L, Long.MIN_VALUE to Long.MAX_VALUE)) {
				assertNull("$mode: $high / $low", price(mode, high, low))
			}
		}
	}

	@Test
	fun adapterPricesRequireUsdAndDoNotUseReflectedCurrency() {
		for (mode in adapterModes) {
			for (currency in listOf("EUR", "", "usd")) {
				assertNull("$mode: $currency", resolveComparisonPriceEcpmMicros(
					config(mode, currency), reflectedPrice, 30, 20,
				))
			}
			assertEquals(price(mode, 30, 20), resolveComparisonPriceEcpmMicros(
				config(mode), reflectedPrice.copy(currencyCode = "EUR"), 30, 20,
			))
		}
	}

	@Test
	fun reflectModeUsesImpressionMicrosConvertedToEcpmWithoutAdapterFallback() {
		assertEquals(25_000_000L, resolveComparisonPriceEcpmMicros(
			config(ProbeMod.REFLECT, "EUR"), reflectedPrice, 0, -1,
		))
		assertNull(resolveComparisonPriceEcpmMicros(config(ProbeMod.REFLECT), null, 30, 20))
		for (invalid in listOf(
			reflectedPrice.copy(currencyCode = "EUR"),
			reflectedPrice.copy(valueMicros = 0),
			reflectedPrice.copy(valueMicros = -1),
			reflectedPrice.copy(valueMicros = Long.MIN_VALUE),
		)) {
			assertNull(invalid.toString(), resolveComparisonPriceEcpmMicros(
				config(ProbeMod.REFLECT), invalid, 30, 20,
			))
		}
	}

	@Test
	fun reflectAndAdapterModesReturnTheSameUnit() {
		val reflected = resolveComparisonPriceEcpmMicros(config(ProbeMod.REFLECT), reflectedPrice, null, null)
		assertEquals(reflected, price(ProbeMod.ADAPTER_H, 25_000_000, null))
		assertEquals(reflected, price(ProbeMod.ADAPTER_M, 30_000_000, 20_000_000))
		assertEquals(reflected, price(ProbeMod.ADAPTER_L, null, 25_000_000))
	}

	@Test
	fun midpointDoesNotOverflowWhenBoundsApproachLongMax() {
		assertEquals(Long.MAX_VALUE - 1, price(ProbeMod.ADAPTER_M, Long.MAX_VALUE, Long.MAX_VALUE - 2))
		assertEquals(Long.MAX_VALUE / 2 + 1, price(ProbeMod.ADAPTER_M, Long.MAX_VALUE, 1))
		for (mode in adapterModes) {
			assertEquals(mode.name, Long.MAX_VALUE, price(mode, Long.MAX_VALUE, Long.MAX_VALUE))
		}
	}

	@Test
	fun reflectedConversionRejectsOverflowWithoutRejectingLargestSafeValue() {
		val largestSafe = Long.MAX_VALUE / 1_000L
		assertEquals(largestSafe * 1_000L, resolveComparisonPriceEcpmMicros(
			config(ProbeMod.REFLECT), reflectedPrice.copy(valueMicros = largestSafe), null, null,
		))
		for (value in listOf(largestSafe + 1, Long.MAX_VALUE)) {
			assertNull(resolveComparisonPriceEcpmMicros(
				config(ProbeMod.REFLECT), reflectedPrice.copy(valueMicros = value), null, null,
			))
		}
	}

	private fun price(mode: ProbeMod, high: Long?, low: Long?) =
		resolveComparisonPriceEcpmMicros(config(mode), reflectedPrice, high, low)

	private fun config(mode: ProbeMod, currency: String = "USD") =
		ProbeConfig(mode, 3_000, currency, emptyList())

	private val reflectedPrice = AdmobPrice(25_000, "USD", 1)
	private val adapterModes = listOf(ProbeMod.ADAPTER_H, ProbeMod.ADAPTER_M, ProbeMod.ADAPTER_L)
}

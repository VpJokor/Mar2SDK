package com.mar2sdk.core.ad.impl.admob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdmobPriceProbeTest {

	@Test
	fun readsPrivateFieldsAndConvertsImpressionRevenueToEcpm() {
		val price = extractor().read(Ad(State(Event())))!!

		assertEquals(12_345L, price.valueMicros)
		assertEquals("USD", price.currencyCode)
		assertEquals(1, price.precisionType)
		assertEquals(0.012345, price.revenue, 0.000000001)
		assertEquals(12_345_000L, price.ecpmMicros)
		assertEquals(12.345, price.ecpm, 0.000000001)
	}

	@Test
	fun readsTheExplicitDynamiteEventFieldNames() {
		val extractor = AdmobPriceExtractor(
			emptyList(),
			DynamiteEvent::class.java.name,
			AdmobPriceEventFields(eventType = "a", precision = "b", currency = "c", valueMicros = "d")
		)

		assertEquals(AdmobPrice(12_345L, "USD", 1), extractor.read(DynamiteEvent()))
		assertNull(AdmobPriceExtractor(emptyList(), DynamiteEvent::class.java.name).read(DynamiteEvent()))
		for (event in listOf(
			DynamiteEvent(a = 2),
			DynamiteEvent(b = 0),
			DynamiteEvent(c = "EUR"),
			DynamiteEvent(d = 0),
			DynamiteEvent(d = Long.MAX_VALUE),
		)) {
			assertNull(extractor.read(event))
		}
	}

	@Test
	fun findsPrivateFieldsOnTheSpecifiedSuperclass() {
		val path = listOf(
			field(DerivedAd::class.java, BaseAd::class.java, "state", State::class.java),
			stateField
		)

		assertEquals(
			AdmobPrice(12_345L, "USD", 1),
			AdmobPriceExtractor(path, Event::class.java.name).read(DerivedAd(State(Event())))
		)
	}

	@Test
	fun acceptsEachKnownPrecisionAndTheLargestSafeAmount() {
		for (precision in 1..3) {
			val price = extractor().read(Ad(State(Event(zzb = precision, zzd = Long.MAX_VALUE / 1_000))))!!

			assertEquals(precision, price.precisionType)
			assertEquals(Long.MAX_VALUE / 1_000, price.valueMicros)
			assertEquals(Long.MAX_VALUE / 1_000 * 1_000, price.ecpmMicros)
		}
	}

	@Test
	fun rejectsNonRevenueEventsAndUnknownPrecision() {
		for (eventType in listOf(Int.MIN_VALUE, 0, 1, 2, 4, Int.MAX_VALUE)) {
			assertNull("event type $eventType", extractor().read(Ad(State(Event(zza = eventType)))))
		}
		for (precision in listOf(Int.MIN_VALUE, -1, 0, 4, Int.MAX_VALUE)) {
			assertNull("precision $precision", extractor().read(Ad(State(Event(zzb = precision)))))
		}
	}

	@Test
	fun rejectsMissingOrNonUsdCurrency() {
		for (currency in listOf(null, "", "EUR", "usd", " USD ")) {
			assertNull("currency $currency", extractor().read(Ad(State(Event(zzc = currency)))))
		}
	}

	@Test
	fun rejectsNonPositiveAndOverflowingAmounts() {
		for (amount in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE / 1_000 + 1, Long.MAX_VALUE)) {
			assertNull("amount $amount", extractor().read(Ad(State(Event(zzd = amount)))))
		}
	}

	@Test
	fun returnsNullForMissingObjectsAnywhereInThePath() {
		assertNull(extractor().read(Ad(null)))
		assertNull(extractor().read(Ad(State(null))))
	}

	@Test
	fun refusesToReadFieldsWhenAnyPartOfTheSignatureChanges() {
		val ad = Ad(State(Event()))
		val mismatches = listOf(
			adField.copy(runtimeClassName = BaseAd::class.java.name),
			adField.copy(declaringClassName = BaseAd::class.java.name),
			adField.copy(fieldName = "missing"),
			adField.copy(fieldTypeName = Any::class.java.name)
		)
		for (mismatch in mismatches) {
			assertNull(
				"signature $mismatch",
				AdmobPriceExtractor(listOf(mismatch, stateField), Event::class.java.name).read(ad)
			)
		}
		val wrongIntermediateRuntime = stateField.copy(runtimeClassName = Ad::class.java.name)
		assertNull(AdmobPriceExtractor(listOf(adField, wrongIntermediateRuntime), Event::class.java.name).read(ad))
		assertNull(AdmobPriceExtractor(listOf(adField, stateField), Any::class.java.name).read(ad))
	}

	@Test
	fun refusesStaticFields() {
		val path = listOf(
			field(StaticAd::class.java, StaticAd::class.java, "state", State::class.java),
			stateField
		)

		assertNull(AdmobPriceExtractor(path, Event::class.java.name).read(StaticAd()))
	}

	@Test
	fun rejectsMalformedEventFieldsWithoutThrowing() {
		val malformedEvents = listOf(MissingEventFields(), WrongAmountType(), WrongPrecisionType())

		for (event in malformedEvents) {
			assertNull(AdmobPriceExtractor(emptyList(), event.javaClass.name).read(event))
		}
	}

	@Test
	fun returnsNullForUnsupportedSdkVersionsAndAdClasses() {
		for (version in listOf("", "25.2.0", "25.3.1", "26.0.0")) {
			assertNull(AdmobPriceProbe.read(Ad(State(Event())), version))
		}
		assertNull(AdmobPriceProbe.read(Any(), "25.3.0"))
	}

	private fun extractor() = AdmobPriceExtractor(listOf(adField, stateField), Event::class.java.name)

	private val adField = field(Ad::class.java, Ad::class.java, "state", State::class.java)
	private val stateField = field(State::class.java, State::class.java, "event", Event::class.java)

	private fun field(runtimeClass: Class<*>, declaringClass: Class<*>, name: String, type: Class<*>) =
		AdmobPriceField(runtimeClass.name, declaringClass.name, name, type.name)

	private class Ad(private val state: State?)
	private open class BaseAd(private val state: State?)
	private class DerivedAd(state: State?) : BaseAd(state)
	private class State(private val event: Event?)
	private class Event(
		private val zza: Int = 3,
		private val zzb: Int = 1,
		private val zzc: String? = "USD",
		private val zzd: Long = 12_345L
	)
	private class DynamiteEvent(
		private val a: Int = 3,
		private val b: Int = 1,
		private val c: String = "USD",
		private val d: Long = 12_345L,
	)

	private class StaticAd {
		companion object {
			@JvmField
			val state = State(Event())
		}
	}

	private class MissingEventFields
	private class WrongAmountType(
		private val zza: Int = 3,
		private val zzb: Int = 1,
		private val zzc: String = "USD",
		private val zzd: Int = 12_345
	)
	private class WrongPrecisionType(
		private val zza: Int = 3,
		private val zzb: Long = 1L,
		private val zzc: String = "USD",
		private val zzd: Long = 12_345L
	)
}

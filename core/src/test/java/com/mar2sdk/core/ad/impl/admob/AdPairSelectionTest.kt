package com.mar2sdk.core.ad.impl.admob

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class AdPairSelectionTest(
	private val primaryFormat: String,
	private val secondaryFormat: String,
) {

	@Test
	fun cachedAdsChooseTheHigherPriceWithoutLoading() = runTest {
		for ((primaryPrice, secondaryPrice) in listOf(200L to 100L, 100L to 200L)) {
			val primaryAd = Ad(primaryFormat, primaryPrice)
			val secondaryAd = Ad(secondaryFormat, secondaryPrice)

			val result = selectAdPair(
				primaryAd = primaryAd,
				secondaryAd = secondaryAd,
				maxWaitTimeMs = 1_000L,
				loadPrimary = { error("Cached primary ad must skip loading") },
				loadSecondary = { error("Cached secondary ad must skip loading") },
				priceMicros = Ad::price,
			)

			assertSame(if (primaryPrice > secondaryPrice) primaryAd else secondaryAd, result.ad)
			assertFalse(result.timedOut)
		}
		assertEquals(0L, currentTime)
	}

	@Test
	fun cachedAdsPreferPrimaryWhenPricesAreEqualOrEitherPriceIsUnknown() = runTest {
		val prices = listOf<Pair<Long?, Long?>>(
			100L to 100L,
			null to 200L,
			200L to null,
			null to null,
		)
		for ((primaryPrice, secondaryPrice) in prices) {
			val primaryAd = Ad(primaryFormat, primaryPrice)
			val secondaryAd = Ad(secondaryFormat, secondaryPrice)

			val result = selectAdPair(
				primaryAd = primaryAd,
				secondaryAd = secondaryAd,
				maxWaitTimeMs = 1_000L,
				loadPrimary = { error("Cached primary ad must skip loading") },
				loadSecondary = { error("Cached secondary ad must skip loading") },
				priceMicros = Ad::price,
			)

			assertSame("Prices: $primaryPrice / $secondaryPrice", primaryAd, result.ad)
			assertFalse(result.timedOut)
		}
		assertEquals(0L, currentTime)
	}

	@Test
	fun cachedPrimaryWaitsForSecondaryBeforeComparingPrices() = runTest {
		val primaryAd = Ad(primaryFormat, 100L)
		val secondaryAd = Ad(secondaryFormat, 200L)

		val result = selectAdPair(
			primaryAd = primaryAd,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { error("Cached primary ad must skip loading") },
			loadSecondary = { delay(300L); secondaryAd },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(300L, currentTime)
	}

	@Test
	fun cachedSecondaryWaitsForPrimaryBeforeComparingPrices() = runTest {
		val primaryAd = Ad(primaryFormat, 200L)
		val secondaryAd = Ad(secondaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = secondaryAd,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { delay(300L); primaryAd },
			loadSecondary = { error("Cached secondary ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(primaryAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(300L, currentTime)
	}

	@Test
	fun cachedPrimaryIsUsedWhenSecondaryTimesOut() = runTest {
		val primaryAd = Ad(primaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = primaryAd,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { error("Cached primary ad must skip loading") },
			loadSecondary = { awaitCancellation() },
			priceMicros = Ad::price,
		)

		assertSame(primaryAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun cachedSecondaryIsUsedWhenPrimaryTimesOut() = runTest {
		val secondaryAd = Ad(secondaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = secondaryAd,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { awaitCancellation() },
			loadSecondary = { error("Cached secondary ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun emptyPoolsLoadBothFormatsConcurrentlyWithinOneDeadline() = runTest {
		val primaryAd = Ad(primaryFormat, 100L)
		val secondaryAd = Ad(secondaryFormat, 200L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { delay(600L); primaryAd },
			loadSecondary = { delay(800L); secondaryAd },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(800L, currentTime)
	}

	@Test
	fun loadedPrimarySurvivesTimeoutWhileSecondaryIsStillPending() = runTest {
		val primaryAd = Ad(primaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { delay(200L); primaryAd },
			loadSecondary = { awaitCancellation() },
			priceMicros = Ad::price,
		)

		assertSame(primaryAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun loadedSecondarySurvivesTimeoutWhilePrimaryIsStillPending() = runTest {
		val secondaryAd = Ad(secondaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { awaitCancellation() },
			loadSecondary = { delay(200L); secondaryAd },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun bothFailedLoadsReturnWithoutWaitingForTheDeadline() = runTest {
		val result = selectAdPair<Ad>(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { delay(100L); null },
			loadSecondary = { delay(200L); null },
			priceMicros = { error("Failed loads have no price") },
		)

		assertNull(result.ad)
		assertFalse(result.timedOut)
		assertEquals(200L, currentTime)
	}

	@Test
	fun oneFailedLoadDoesNotDiscardTheOtherSuccessfulLoad() = runTest {
		val secondaryAd = Ad(secondaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { delay(100L); null },
			loadSecondary = { delay(200L); secondaryAd },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(200L, currentTime)
	}

	@Test
	fun timeoutDoesNotCancelIndependentLoadsOrSelectTheirLateResults() = runTest {
		val primaryLoad = CompletableDeferred<Ad>()
		val secondaryLoad = CompletableDeferred<Ad>()
		var completedWaiters = 0
		var priceReads = 0

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = null,
			maxWaitTimeMs = 1_000L,
			loadPrimary = { primaryLoad.await().also { completedWaiters++ } },
			loadSecondary = { secondaryLoad.await().also { completedWaiters++ } },
			priceMicros = { priceReads++; it.price },
		)

		assertNull(result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
		assertTrue(primaryLoad.isActive)
		assertTrue(secondaryLoad.isActive)
		assertTrue(primaryLoad.complete(Ad(primaryFormat, 100L)))
		assertTrue(secondaryLoad.complete(Ad(secondaryFormat, 200L)))
		runCurrent()
		assertEquals(0, completedWaiters)
		assertEquals(0, priceReads)
	}

	@Test
	fun externalCancellationStopsWaitingWithoutSelectingLateResults() = runTest {
		val primaryLoad = CompletableDeferred<Ad>()
		val secondaryLoad = CompletableDeferred<Ad>()
		var result: AdPairSelection<Ad>? = null
		var startedWaiters = 0
		val request = launch {
			result = selectAdPair(
				primaryAd = null,
				secondaryAd = null,
				maxWaitTimeMs = 1_000L,
				loadPrimary = { startedWaiters++; primaryLoad.await() },
				loadSecondary = { startedWaiters++; secondaryLoad.await() },
				priceMicros = { error("Canceled selection must not compare prices") },
			)
		}
		runCurrent()
		assertEquals(2, startedWaiters)

		request.cancelAndJoin()

		assertTrue(request.isCancelled)
		assertNull(result)
		assertTrue(primaryLoad.isActive)
		assertTrue(secondaryLoad.isActive)
		primaryLoad.complete(Ad(primaryFormat, 100L))
		secondaryLoad.complete(Ad(secondaryFormat, 200L))
		runCurrent()
		assertNull(result)
		assertEquals(0L, currentTime)
	}

	@Test
	fun zeroTimeoutImmediatelyUsesTheCachedCandidate() = runTest {
		val secondaryAd = Ad(secondaryFormat, 100L)

		val result = selectAdPair(
			primaryAd = null,
			secondaryAd = secondaryAd,
			maxWaitTimeMs = 0L,
			loadPrimary = { error("Zero timeout must not wait for a load") },
			loadSecondary = { error("Cached secondary ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(secondaryAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(0L, currentTime)
	}

	companion object {
		@JvmStatic
		@Parameterized.Parameters(name = "{0}/{1}")
		fun formats(): List<Array<String>> = listOf(
			arrayOf("open", "inter"),
			arrayOf("inter", "video"),
		)
	}

	private data class Ad(val format: String, val price: Long?)
}

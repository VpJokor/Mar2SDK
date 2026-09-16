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

@OptIn(ExperimentalCoroutinesApi::class)
class OpenInterSelectionTest {

	@Test
	fun cachedAdsChooseTheHigherPriceWithoutLoading() = runTest {
		for ((openPrice, interPrice) in listOf(200L to 100L, 100L to 200L)) {
			val openAd = Ad("open", openPrice)
			val interAd = Ad("inter", interPrice)

			val result = selectOpenInterAd(
				openAd = openAd,
				interAd = interAd,
				maxWaitTimeMs = 1_000L,
				loadOpen = { error("Cached open ad must skip loading") },
				loadInter = { error("Cached interstitial ad must skip loading") },
				priceMicros = Ad::price,
			)

			assertSame(if (openPrice > interPrice) openAd else interAd, result.ad)
			assertFalse(result.timedOut)
		}
		assertEquals(0L, currentTime)
	}

	@Test
	fun cachedAdsPreferOpenWhenPricesAreEqualOrEitherPriceIsUnknown() = runTest {
		val prices = listOf<Pair<Long?, Long?>>(
			100L to 100L,
			null to 200L,
			200L to null,
			null to null,
		)
		for ((openPrice, interPrice) in prices) {
			val openAd = Ad("open", openPrice)
			val interAd = Ad("inter", interPrice)

			val result = selectOpenInterAd(
				openAd = openAd,
				interAd = interAd,
				maxWaitTimeMs = 1_000L,
				loadOpen = { error("Cached open ad must skip loading") },
				loadInter = { error("Cached interstitial ad must skip loading") },
				priceMicros = Ad::price,
			)

			assertSame("Prices: $openPrice / $interPrice", openAd, result.ad)
			assertFalse(result.timedOut)
		}
		assertEquals(0L, currentTime)
	}

	@Test
	fun cachedOpenWaitsForInterstitialBeforeComparingPrices() = runTest {
		val openAd = Ad("open", 100L)
		val interAd = Ad("inter", 200L)

		val result = selectOpenInterAd(
			openAd = openAd,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { error("Cached open ad must skip loading") },
			loadInter = { delay(300L); interAd },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(300L, currentTime)
	}

	@Test
	fun cachedInterstitialWaitsForOpenBeforeComparingPrices() = runTest {
		val openAd = Ad("open", 200L)
		val interAd = Ad("inter", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = interAd,
			maxWaitTimeMs = 1_000L,
			loadOpen = { delay(300L); openAd },
			loadInter = { error("Cached interstitial ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(openAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(300L, currentTime)
	}

	@Test
	fun cachedOpenIsUsedWhenInterstitialTimesOut() = runTest {
		val openAd = Ad("open", 100L)

		val result = selectOpenInterAd(
			openAd = openAd,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { error("Cached open ad must skip loading") },
			loadInter = { awaitCancellation() },
			priceMicros = Ad::price,
		)

		assertSame(openAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun cachedInterstitialIsUsedWhenOpenTimesOut() = runTest {
		val interAd = Ad("inter", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = interAd,
			maxWaitTimeMs = 1_000L,
			loadOpen = { awaitCancellation() },
			loadInter = { error("Cached interstitial ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun emptyPoolsLoadBothFormatsConcurrentlyWithinOneDeadline() = runTest {
		val openAd = Ad("open", 100L)
		val interAd = Ad("inter", 200L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { delay(600L); openAd },
			loadInter = { delay(800L); interAd },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(800L, currentTime)
	}

	@Test
	fun loadedOpenSurvivesTimeoutWhileInterstitialIsStillPending() = runTest {
		val openAd = Ad("open", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { delay(200L); openAd },
			loadInter = { awaitCancellation() },
			priceMicros = Ad::price,
		)

		assertSame(openAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun loadedInterstitialSurvivesTimeoutWhileOpenIsStillPending() = runTest {
		val interAd = Ad("inter", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { awaitCancellation() },
			loadInter = { delay(200L); interAd },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
	}

	@Test
	fun bothFailedLoadsReturnWithoutWaitingForTheDeadline() = runTest {
		val result = selectOpenInterAd<Ad>(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { delay(100L); null },
			loadInter = { delay(200L); null },
			priceMicros = { error("Failed loads have no price") },
		)

		assertNull(result.ad)
		assertFalse(result.timedOut)
		assertEquals(200L, currentTime)
	}

	@Test
	fun oneFailedLoadDoesNotDiscardTheOtherSuccessfulLoad() = runTest {
		val interAd = Ad("inter", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { delay(100L); null },
			loadInter = { delay(200L); interAd },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertFalse(result.timedOut)
		assertEquals(200L, currentTime)
	}

	@Test
	fun timeoutDoesNotCancelIndependentLoadsOrSelectTheirLateResults() = runTest {
		val openLoad = CompletableDeferred<Ad>()
		val interLoad = CompletableDeferred<Ad>()
		var completedWaiters = 0
		var priceReads = 0

		val result = selectOpenInterAd(
			openAd = null,
			interAd = null,
			maxWaitTimeMs = 1_000L,
			loadOpen = { openLoad.await().also { completedWaiters++ } },
			loadInter = { interLoad.await().also { completedWaiters++ } },
			priceMicros = { priceReads++; it.price },
		)

		assertNull(result.ad)
		assertTrue(result.timedOut)
		assertEquals(1_000L, currentTime)
		assertTrue(openLoad.isActive)
		assertTrue(interLoad.isActive)
		assertTrue(openLoad.complete(Ad("open", 100L)))
		assertTrue(interLoad.complete(Ad("inter", 200L)))
		runCurrent()
		assertEquals(0, completedWaiters)
		assertEquals(0, priceReads)
	}

	@Test
	fun externalCancellationStopsWaitingWithoutSelectingLateResults() = runTest {
		val openLoad = CompletableDeferred<Ad>()
		val interLoad = CompletableDeferred<Ad>()
		var result: OpenInterSelection<Ad>? = null
		var startedWaiters = 0
		val request = launch {
			result = selectOpenInterAd(
				openAd = null,
				interAd = null,
				maxWaitTimeMs = 1_000L,
				loadOpen = { startedWaiters++; openLoad.await() },
				loadInter = { startedWaiters++; interLoad.await() },
				priceMicros = { error("Canceled selection must not compare prices") },
			)
		}
		runCurrent()
		assertEquals(2, startedWaiters)

		request.cancelAndJoin()

		assertTrue(request.isCancelled)
		assertNull(result)
		assertTrue(openLoad.isActive)
		assertTrue(interLoad.isActive)
		openLoad.complete(Ad("open", 100L))
		interLoad.complete(Ad("inter", 200L))
		runCurrent()
		assertNull(result)
		assertEquals(0L, currentTime)
	}

	@Test
	fun zeroTimeoutImmediatelyUsesTheCachedCandidate() = runTest {
		val interAd = Ad("inter", 100L)

		val result = selectOpenInterAd(
			openAd = null,
			interAd = interAd,
			maxWaitTimeMs = 0L,
			loadOpen = { error("Zero timeout must not wait for a load") },
			loadInter = { error("Cached interstitial ad must skip loading") },
			priceMicros = Ad::price,
		)

		assertSame(interAd, result.ad)
		assertTrue(result.timedOut)
		assertEquals(0L, currentTime)
	}

	private data class Ad(val format: String, val price: Long?)
}

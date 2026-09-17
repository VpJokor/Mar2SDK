package com.mar2sdk.core.common

import com.mar2sdk.core.common.net.ServerApiException
import com.mar2sdk.core.common.net.UserAttributionSync
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserAttributionSyncTest {

	@Test
	fun waitsForBothLoginAndAttributionInEitherOrder() = runTest {
		for (loginFirst in listOf(false, true)) {
			val fixture = SyncFixture()
			if (loginFirst) fixture.sync.onLogin(42) else fixture.attribution = attribution()

			fixture.sync.sync()
			assertTrue(fixture.uploads.isEmpty())

			if (loginFirst) fixture.attribution = attribution() else fixture.sync.onLogin(42)
			fixture.sync.sync()
			assertEquals(listOf(42L), fixture.uploads.map { it.first })
			assertSame(fixture.attribution, fixture.uploads.single().second)
		}
	}

	@Test
	fun laterLoginAndExplicitSyncCanUploadTheSameAttributionAgain() = runTest {
		val fixture = SyncFixture(attribution())
		fixture.sync.onLogin(42)
		fixture.sync.sync()
		fixture.sync.onLogin(42)
		fixture.sync.sync()
		fixture.sync.sync()
		fixture.sync.onLogin(43)
		fixture.sync.sync()

		assertEquals(listOf(42L, 42L, 42L, 43L), fixture.uploads.map { it.first })
		fixture.uploads.forEach { assertSame(fixture.attribution, it.second) }
	}

	@Test
	fun businessAndIoFailuresRetryAtMostThreeTimesWithBackoffAndPreserveAttribution() = runTest {
		for (failure in listOf(IOException("Offline"), ServerApiException(1025, "Unavailable"))) {
			val saved = attribution()
			val fixture = SyncFixture(saved)
			val attemptTimes = mutableListOf<Long>()
			val startTime = currentTime
			fixture.response = {
				attemptTimes += currentTime - startTime
				throw failure
			}
			fixture.sync.onLogin(42)

			fixture.sync.sync()

			assertEquals(listOf(0L, 1_000L, 3_000L), attemptTimes)
			assertEquals(listOf(failure, failure, failure), fixture.failures)
			assertSame(saved, fixture.attribution)
			fixture.response = {}
			fixture.sync.sync()
			assertEquals(4, fixture.uploads.size)
		}
	}

	@Test
	fun unexpectedFailuresAreReportedWithoutRetrying() = runTest {
		val failure = IllegalArgumentException("Invalid attribution")
		val fixture = SyncFixture(attribution())
		fixture.response = { throw failure }
		fixture.sync.onLogin(42)

		fixture.sync.sync()

		assertEquals(1, fixture.uploads.size)
		assertEquals(listOf(failure), fixture.failures)
		assertEquals(0L, currentTime)
	}

	@Test
	fun attributionLoadingFailuresAreReportedWithoutLosingTheLoggedInUser() = runTest {
		val saved = attribution()
		val failure = IllegalStateException("Unreadable attribution")
		var failLoading = true
		val failures = mutableListOf<Exception>()
		val uploadedUsers = mutableListOf<Long>()
		val sync = UserAttributionSync(
			loadAttribution = { if (failLoading) throw failure else saved },
			upload = { uid, _ -> uploadedUsers += uid },
			onFailure = { failures += it },
		)
		sync.onLogin(42)

		sync.sync()
		assertEquals(listOf(failure), failures)
		assertTrue(uploadedUsers.isEmpty())
		failLoading = false
		sync.sync()
		assertEquals(listOf(42L), uploadedUsers)
	}

	@Test
	fun cancellationDuringUploadPropagatesWithoutRetryingOrReportingFailure() = runTest {
		val fixture = SyncFixture(attribution())
		val failure = CancellationException("Cancelled")
		fixture.response = { throw failure }
		fixture.sync.onLogin(42)

		val thrown = try {
			fixture.sync.sync()
			null
		} catch (error: CancellationException) {
			error
		}

		assertSame(failure, thrown)
		assertEquals(1, fixture.uploads.size)
		assertTrue(fixture.failures.isEmpty())
	}

	@Test
	fun cancellationDuringBackoffStopsRetrying() = runTest {
		val fixture = SyncFixture(attribution())
		fixture.response = { throw IOException("Offline") }
		fixture.sync.onLogin(42)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.sync.sync() }

		job.cancelAndJoin()
		advanceUntilIdle()

		assertEquals(1, fixture.uploads.size)
		assertEquals(1, fixture.failures.size)
	}

	@Test
	fun clearingOrChangingUserDuringBackoffStopsThePreviousUpload() = runTest {
		for (clearUser in listOf(false, true)) {
			val fixture = SyncFixture(attribution())
			fixture.response = { throw IOException("Offline") }
			fixture.sync.onLogin(42)
			val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.sync.sync() }

			if (clearUser) fixture.sync.clearUser() else fixture.sync.onLogin(43)
			job.join()

			assertEquals(listOf(42L), fixture.uploads.map { it.first })
			fixture.response = {}
			fixture.sync.sync()
			assertEquals(if (clearUser) listOf(42L) else listOf(42L, 43L), fixture.uploads.map { it.first })
		}
	}

	@Test
	fun retriesReadTheLatestAttribution() = runTest {
		val fixture = SyncFixture(attribution("first"))
		fixture.response = { if (fixture.uploads.size == 1) throw IOException("Retry") }
		fixture.sync.onLogin(42)
		val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.sync.sync() }
		fixture.attribution = attribution("updated")

		job.join()

		assertEquals(listOf("first", "updated"), fixture.uploads.map { it.second.getString("campaign") })
	}

	@Test
	fun concurrentSyncCallsWaitForTheCurrentUpload() = runTest {
		val fixture = SyncFixture(attribution())
		val releaseUpload = CompletableDeferred<Unit>()
		fixture.response = { if (fixture.uploads.size == 1) releaseUpload.await() }
		fixture.sync.onLogin(42)
		val first = launch { fixture.sync.sync() }
		val second = launch { fixture.sync.sync() }
		runCurrent()
		assertEquals(1, fixture.uploads.size)

		releaseUpload.complete(Unit)
		first.join()
		second.join()

		assertEquals(2, fixture.uploads.size)
	}

	private fun attribution(campaign: String = "saved"): JSONObject = JSONObject().put("campaign", campaign)

	private class SyncFixture(var attribution: JSONObject? = null) {
		val uploads = mutableListOf<Pair<Long, JSONObject>>()
		val failures = mutableListOf<Exception>()
		var response: suspend () -> Unit = {}
		val sync = UserAttributionSync(
			loadAttribution = { attribution },
			upload = { uid, snapshot ->
				uploads += uid to snapshot
				response()
			},
			onFailure = { failures += it },
		)
	}
}

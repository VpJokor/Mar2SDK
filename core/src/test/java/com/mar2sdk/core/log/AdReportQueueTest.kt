package com.mar2sdk.core.log

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdReportQueueTest {
	@Test
	fun smallBatchesWaitFiveSecondsWithoutExtendingTheFirstWindow() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.queue.enqueue(event("first"))
		runCurrent()
		advanceTimeBy(4_000)
		fixture.queue.enqueue(event("second"))
		runCurrent()
		assertEquals(2, fixture.storage.events.size)
		assertTrue(fixture.uploads.isEmpty())
		advanceTimeBy(1_000)
		runCurrent()
		assertEquals(listOf(listOf("first", "second")), fixture.uploads.map { it.ids() })
		assertTrue(fixture.storage.events.isEmpty())
	}

	@Test
	fun twentiethEventFlushesImmediately() = runTest {
		val fixture = Fixture(backgroundScope)
		repeat(19) { fixture.queue.enqueue(event("event-$it")) }
		runCurrent()
		assertTrue(fixture.uploads.isEmpty())
		fixture.queue.enqueue(event("event-19"))
		runCurrent()
		assertEquals(20, fixture.uploads.single().body.length())
		assertEquals(0L, currentTime)
	}

	@Test
	fun configuredThreeEventThresholdAndTwoSecondIntervalAreUsed() = runTest {
		val fixture = Fixture(backgroundScope, batchSize = 3, flushDelayMillis = 2_000)
		repeat(2) { fixture.queue.enqueue(event("timed-$it")) }
		runCurrent()
		advanceTimeBy(1_999)
		runCurrent()
		assertTrue(fixture.uploads.isEmpty())
		advanceTimeBy(1)
		runCurrent()
		assertEquals(listOf("timed-0", "timed-1"), fixture.uploads.single().ids())

		repeat(3) { fixture.queue.enqueue(event("immediate-$it")) }
		runCurrent()
		assertEquals(listOf("immediate-0", "immediate-1", "immediate-2"), fixture.uploads.last().ids())
		assertEquals(2_000L, currentTime)
	}

	@Test
	fun changingBatchSizeUpdatesTheNextThresholdCheckAndBatchLimit() = runTest {
		val fixture = Fixture(backgroundScope)
		repeat(2) { fixture.queue.enqueue(event("threshold-$it")) }
		runCurrent()
		assertTrue(fixture.uploads.isEmpty())
		fixture.batchSize = 3
		fixture.queue.enqueue(event("threshold-2"))
		runCurrent()
		assertEquals(3, fixture.uploads.single().body.length())
		assertEquals(0L, currentTime)

		fixture.batchSize = 2
		repeat(7) { fixture.storage.insert(event("stored-$it")) }
		fixture.queue.flush()
		runCurrent()
		assertEquals(listOf(2, 2, 2, 1), fixture.uploads.drop(1).map { it.body.length() })
		assertTrue(fixture.storage.events.isEmpty())
	}

	@Test
	fun changingIntervalTakesEffectInTheNextWindowAndPreservesTheCurrentTimer() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.queue.enqueue(event("current-window"))
		runCurrent()
		advanceTimeBy(1_000)
		fixture.flushDelayMillis = 2_000
		advanceTimeBy(3_999)
		runCurrent()
		assertTrue(fixture.uploads.isEmpty())
		advanceTimeBy(1)
		runCurrent()
		assertEquals(listOf("current-window"), fixture.uploads.single().ids())

		fixture.queue.enqueue(event("next-window"))
		runCurrent()
		advanceTimeBy(1_999)
		runCurrent()
		assertEquals(1, fixture.uploads.size)
		advanceTimeBy(1)
		runCurrent()
		assertEquals(listOf("next-window"), fixture.uploads.last().ids())
		assertEquals(7_000L, currentTime)
	}

	@Test
	fun changingBatchSizeDuringRetryPreservesThatBatchAndLimitsFollowingBatches() = runTest {
		val fixture = Fixture(backgroundScope, batchSize = 3)
		repeat(5) { fixture.storage.insert(event("stored-$it")) }
		fixture.response = { _, _ ->
			if (fixture.uploads.size == 1) {
				fixture.batchSize = 1
				Result.failure(IOException("Retry this batch"))
			} else Result.success(Unit)
		}
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(1_000)
		runCurrent()
		assertEquals(listOf(3, 3, 1, 1), fixture.uploads.map { it.body.length() })
		assertEquals(fixture.uploads[0].body.toString(), fixture.uploads[1].body.toString())
		assertTrue(fixture.storage.events.isEmpty())
	}

	@Test
	fun retriesKeepOriginalValuesAndIdsAndStopAfterThreeAttempts() = runTest {
		val fixture = Fixture(backgroundScope)
		val attempts = mutableListOf<Long>()
		fixture.response = { _, body ->
			attempts += currentTime
			body.getJSONObject(0).put("#event_id", "mutated-by-sender")
			Result.failure(IOException("Offline"))
		}
		val original = event("persistent-id")
		fixture.queue.enqueue(original)
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(1_000)
		runCurrent()
		advanceTimeBy(2_000)
		runCurrent()
		advanceTimeBy(120_000)
		runCurrent()
		assertEquals(listOf(0L, 1_000L, 3_000L), attempts)
		assertEquals(listOf(original), fixture.storage.events)
		assertEquals(1, fixture.uploads.map { it.body.toString() }.distinct().size)
		assertEquals(listOf("persistent-id"), fixture.uploads.first().ids())
	}

	@Test
	fun explicitRetryWaitsForCooldownThenRecoversTheRetainedBatch() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.response = { _, _ -> Result.failure(IOException("Offline")) }
		fixture.queue.enqueue(event("retained"))
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(3_000)
		runCurrent()
		assertEquals(3, fixture.uploads.size)
		fixture.response = { _, _ -> Result.success(Unit) }
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(59_999)
		runCurrent()
		assertEquals(3, fixture.uploads.size)
		advanceTimeBy(1)
		runCurrent()
		assertEquals(4, fixture.uploads.size)
		assertTrue(fixture.storage.events.isEmpty())
	}

	@Test
	fun onlyAcceptedBatchIsDeletedWhenTheNextBatchFails() = runTest {
		val fixture = Fixture(backgroundScope, batchSize = 2)
		repeat(5) { fixture.storage.insert(event("event-$it")) }
		fixture.response = { _, body ->
			if (body.getJSONObject(0).getString("#event_id") == "event-0") Result.success(Unit)
			else Result.failure(IOException("Offline"))
		}
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(3_000)
		runCurrent()
		assertEquals(listOf("event-0", "event-1"), fixture.storage.removed)
		assertEquals(listOf("event-2", "event-3", "event-4"), fixture.storage.events.map { it.id })
		assertEquals(4, fixture.uploads.size)
	}

	@Test
	fun cancellationReturnedBySenderPreservesEventsAndDoesNotRetry() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.response = { _, _ -> Result.failure(CancellationException("Cancelled")) }
		fixture.queue.enqueue(event("retained"))
		fixture.queue.flush()
		runCurrent()
		advanceTimeBy(120_000)
		runCurrent()
		assertEquals(1, fixture.uploads.size)
		assertEquals(listOf("retained"), fixture.storage.events.map { it.id })
		assertTrue(fixture.failures.isEmpty())
	}

	@Test
	fun cancelledSendsAllowLaterFlushesForBothThrownAndReturnedCancellation() = runTest {
		for (throwCancellation in listOf(false, true)) {
			val fixture = Fixture(backgroundScope)
			val cancellation = CancellationException("Only this request was cancelled")
			fixture.response = { _, _ ->
				if (throwCancellation) throw cancellation
				Result.failure(cancellation)
			}
			fixture.queue.enqueue(event("retained"))
			fixture.queue.flush()
			runCurrent()
			advanceTimeBy(120_000)
			runCurrent()
			assertEquals(1, fixture.uploads.size)
			assertEquals(listOf("retained"), fixture.storage.events.map { it.id })

			fixture.response = { _, _ -> Result.success(Unit) }
			fixture.queue.enqueue(event("after-cancellation"))
			fixture.queue.flush()
			runCurrent()
			assertEquals(2, fixture.uploads.size)
			assertEquals(listOf("retained", "after-cancellation"), fixture.uploads.last().ids())
			assertTrue(fixture.storage.events.isEmpty())
			assertTrue(fixture.failures.isEmpty())
		}
	}

	@Test
	fun cancellingDuringUploadPreservesTheUnacknowledgedBatch() = runTest {
		val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
		val fixture = Fixture(owner)
		val gate = CompletableDeferred<Unit>()
		fixture.response = { _, _ -> gate.await(); Result.success(Unit) }
		fixture.queue.enqueue(event("in-flight"))
		fixture.queue.flush()
		runCurrent()
		owner.cancel()
		runCurrent()
		assertEquals(listOf("in-flight"), fixture.storage.events.map { it.id })
		assertTrue(fixture.storage.removed.isEmpty())
	}

	@Test
	fun concurrentInsertsPersistWhileUploadWaitsAndUploadsNeverOverlap() = runTest {
		val fixture = Fixture(backgroundScope, batchSize = 1)
		val gate = CompletableDeferred<Unit>()
		var activeUploads = 0
		var maximumUploads = 0
		fixture.response = { _, _ ->
			activeUploads++
			maximumUploads = maxOf(maximumUploads, activeUploads)
			gate.await()
			activeUploads--
			Result.success(Unit)
		}
		fixture.queue.enqueue(event("first"))
		runCurrent()
		val inserts = (1..40).map { fixture.queue.enqueue(event("concurrent-$it")) }
		repeat(10) { fixture.queue.flush() }
		runCurrent()
		assertTrue(inserts.all { it.isCompleted })
		assertEquals(41, fixture.storage.events.size)
		assertEquals(1, fixture.uploads.size)
		gate.complete(Unit)
		runCurrent()
		assertTrue(fixture.storage.events.isEmpty())
		assertEquals(41, fixture.storage.removed.distinct().size)
		assertEquals(1, maximumUploads)
	}

	@Test
	fun aNewQueueResumesPersistedEventsAfterRestart() = runTest {
		val storage = MemoryStore()
		val oldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
		val old = Fixture(oldScope, storage)
		old.queue.enqueue(event("before-restart"))
		runCurrent()
		oldScope.cancel()
		val restarted = Fixture(backgroundScope, storage)
		restarted.queue.flush()
		runCurrent()
		assertTrue(old.uploads.isEmpty())
		assertEquals(listOf("before-restart"), restarted.uploads.single().ids())
		assertTrue(storage.events.isEmpty())
	}

	@Test
	fun loginAndAccountSwitchOnlySendEventsForTheCurrentIdentity() = runTest {
		val fixture = Fixture(backgroundScope)
		val other = AdReportIdentity(9, 88)
		fixture.identity = null
		fixture.queue.enqueue(event("account-a"))
		fixture.queue.enqueue(event("account-b", other))
		fixture.queue.flush()
		runCurrent()
		assertTrue(fixture.uploads.isEmpty())
		fixture.identity = other
		fixture.queue.flush()
		runCurrent()
		assertEquals(other, fixture.uploads.single().identity)
		assertEquals(listOf("account-b"), fixture.uploads.single().ids())
		assertEquals(listOf("account-a"), fixture.storage.events.map { it.id })
		fixture.identity = DEFAULT_IDENTITY
		fixture.queue.flush()
		runCurrent()
		assertEquals(listOf("account-a"), fixture.uploads.last().ids())
		assertTrue(fixture.storage.events.isEmpty())
	}

	@Test
	fun switchingAccountDuringBackoffStopsRetriesForTheOldAccount() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.response = { _, _ -> Result.failure(IOException("Offline")) }
		fixture.queue.enqueue(event("account-a"))
		fixture.queue.flush()
		runCurrent()
		fixture.identity = AdReportIdentity(9, 88)
		advanceTimeBy(3_000)
		runCurrent()
		assertEquals(1, fixture.uploads.size)
		assertEquals(listOf("account-a"), fixture.storage.events.map { it.id })
	}

	@Test
	fun batchLimitCountsUtf8BytesAndEventLimitRejectsOversizedData() = runTest {
		val fixture = Fixture(backgroundScope)
		repeat(6) { index ->
			fixture.queue.enqueue(event("large-$index", detail = "广".repeat(20_000)))
		}
		fixture.queue.enqueue(event("too-large", detail = "广".repeat(22_000)))
		fixture.queue.flush()
		runCurrent()
		assertEquals(listOf(4, 2), fixture.uploads.map { it.body.length() })
		assertTrue(fixture.uploads.all { it.body.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024 })
		assertEquals(1, fixture.failures.size)
		assertFalse(fixture.storage.removed.contains("too-large"))
	}

	private class Fixture(
		scope: CoroutineScope,
		val storage: MemoryStore = MemoryStore(),
		var batchSize: Int = 20,
		var flushDelayMillis: Long = 5_000,
	) {
		var identity: AdReportIdentity? = DEFAULT_IDENTITY
		val uploads = mutableListOf<Upload>()
		val failures = mutableListOf<Throwable>()
		var response: suspend (AdReportIdentity, JSONArray) -> Result<Unit> = { _, _ -> Result.success(Unit) }
		val queue = AdReportQueue(
			scope = scope,
			storage = storage,
			currentIdentity = { identity },
			send = { identity, body ->
				uploads += Upload(identity, JSONArray(body.toString()))
				response(identity, body)
			},
			onFailure = { failures += it },
			batchSize = { batchSize },
			flushDelayMillis = { flushDelayMillis },
		)
	}

	private class MemoryStore : AdReportStorage {
		val events = mutableListOf<PendingAdReport>()
		val removed = mutableListOf<String>()
		override fun insert(event: PendingAdReport) {
			if (events.none { it.id == event.id }) events += event
		}

		override fun peek(identity: AdReportIdentity, limit: Int, maxBytes: Int): List<PendingAdReport> {
			val result = mutableListOf<PendingAdReport>()
			var bytes = 2
			for (event in events.filter { it.identity == identity }) {
				val nextBytes = event.data.toByteArray(Charsets.UTF_8).size + if (result.isEmpty()) 0 else 1
				if (result.size >= limit || bytes + nextBytes > maxBytes) break
				result += event
				bytes += nextBytes
			}
			return result
		}

		override fun remove(ids: List<String>) {
			removed += ids
			events.removeAll { it.id in ids }
		}
	}

	private data class Upload(val identity: AdReportIdentity, val body: JSONArray) {
		fun ids(): List<String> = (0 until body.length()).map { body.getJSONObject(it).getString("#event_id") }
	}

	private companion object {
		val DEFAULT_IDENTITY = AdReportIdentity(9, 42)

		fun event(id: String, identity: AdReportIdentity = DEFAULT_IDENTITY, detail: String = "original") =
			PendingAdReport(id, identity, JSONObject().apply {
				put("#event_id", id)
				put("#uuid", "uuid-$id")
				put("#time", "2026-09-17 12:34:56.789")
				put("properties", JSONObject().put("detail", detail))
			}.toString())
	}
}

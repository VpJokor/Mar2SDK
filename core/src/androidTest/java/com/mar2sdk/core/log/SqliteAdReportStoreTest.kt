package com.mar2sdk.core.log

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteAdReportStoreTest {
	private lateinit var context: Context
	private lateinit var databaseFile: File
	private lateinit var store: SqliteAdReportStore
	private val identity = AdReportIdentity(appID = 10019, uid = 1542198364433551363L)

	@Before
	fun setUp() {
		context = InstrumentationRegistry.getInstrumentation().targetContext
		databaseFile = File(context.cacheDir, "report-store-${UUID.randomUUID()}.db")
		store = SqliteAdReportStore(context, databaseFile)
	}

	@After
	fun tearDown() {
		store.close()
		SQLiteDatabase.deleteDatabase(databaseFile)
	}

	@Test
	fun persistsOriginalPayloadAndOrderAcrossInstances() {
		val first = event("first", data = "{\"#event_id\":1542198364433551364,\"name\":\"中文&=\",\"extra\":[null,true]}")
		val second = event("second")
		store.insert(first)
		store.insert(second)
		store.close()

		store = SqliteAdReportStore(context, databaseFile)

		assertEquals(listOf(first, second), peek())
	}

	@Test
	fun separatesApplicationsAndAdjacentLargeAccountIds() {
		val first = event("first")
		val otherAccount = event("other-account", identity.copy(uid = identity.uid + 1))
		val otherApplication = event("other-application", identity.copy(appID = identity.appID + 1))
		val last = event("last")
		listOf(first, otherAccount, otherApplication, last).forEach(store::insert)

		assertEquals(listOf(first, last), peek())
		assertEquals(listOf(otherAccount), peek(otherAccount.identity))
		assertEquals(listOf(otherApplication), peek(otherApplication.identity))
	}

	@Test
	fun deletesOnlyExplicitlyAcknowledgedIdsUsingBoundParameters() {
		val first = event("first")
		val second = event("second")
		val quoted = event("' OR 1=1 --")
		val last = event("last")
		listOf(first, second, quoted, last).forEach(store::insert)

		store.remove(emptyList())
		assertEquals(listOf(first, second, quoted, last), peek())
		store.remove(listOf(second.id, quoted.id, "unknown"))

		assertEquals(listOf(first, last), peek())
		store.close()
		store = SqliteAdReportStore(context, databaseFile)
		assertEquals(listOf(first, last), peek())
	}

	@Test
	fun duplicateUuidRetainsOriginalPayloadIdentityAndQueuePosition() {
		val first = event("same-uuid", data = "{\"original\":true}")
		val second = event("second")
		store.insert(first)
		store.insert(second)
		store.insert(first)
		store.insert(first.copy(data = "{\"original\":false}"))
		store.insert(first.copy(identity = identity.copy(uid = identity.uid + 1)))

		assertEquals(listOf(first, second), peek())
		assertTrue(peek(identity.copy(uid = identity.uid + 1)).isEmpty())
	}

	@Test
	fun batchBudgetCountsUtf8BytesArrayBracketsAndCommas() {
		val first = event("first", data = "{\"name\":\"中文😀\"}")
		val second = event("second", data = "{\"name\":\"广告\"}")
		val third = event("third")
		listOf(first, second, third).forEach(store::insert)
		val oneEventBytes = 2 + first.data.toByteArray(Charsets.UTF_8).size
		val twoEventBytes = oneEventBytes + 1 + second.data.toByteArray(Charsets.UTF_8).size

		assertEquals(listOf(first, second), store.peek(identity, 100, twoEventBytes))
		assertEquals(listOf(first), store.peek(identity, 100, twoEventBytes - 1))
		assertEquals(listOf(first), store.peek(identity, 100, oneEventBytes))
		assertTrue(store.peek(identity, 100, oneEventBytes - 1).isEmpty())
		assertEquals(listOf(first), store.peek(identity, 1, twoEventBytes))
		assertEquals(listOf(first, second, third), peek())
	}

	@Test
	fun rejectsOversizedEventInUtf8BytesAndAcceptsExactLimit() {
		val prefix = "{\"message\":\""
		val suffix = "\"}"
		val exactLimit = event("exact", data = prefix + "x".repeat(AD_REPORT_MAX_EVENT_BYTES - prefix.length - suffix.length) + suffix)
		val oversized = event("oversized", data = prefix + "汉".repeat(AD_REPORT_MAX_EVENT_BYTES / 3) + suffix)
		assertEquals(AD_REPORT_MAX_EVENT_BYTES, exactLimit.data.toByteArray(Charsets.UTF_8).size)
		assertTrue(oversized.data.length < AD_REPORT_MAX_EVENT_BYTES)
		assertTrue(oversized.data.toByteArray(Charsets.UTF_8).size > AD_REPORT_MAX_EVENT_BYTES)

		store.insert(exactLimit)
		assertThrows(IllegalArgumentException::class.java) { store.insert(oversized) }

		assertEquals(listOf(exactLimit), peek())
	}

	@Test
	fun nonPositiveLimitsAndBudgetsReturnEmptyWithoutRemovingEvents() {
		val event = event("retained")
		store.insert(event)

		assertTrue(store.peek(identity, 0, AD_REPORT_MAX_BATCH_BYTES).isEmpty())
		assertTrue(store.peek(identity, -1, AD_REPORT_MAX_BATCH_BYTES).isEmpty())
		assertTrue(store.peek(identity, 100, 0).isEmpty())
		assertTrue(store.peek(identity, 100, 1).isEmpty())
		assertEquals(listOf(event), peek())
	}

	private fun event(
		id: String,
		identity: AdReportIdentity = this.identity,
		data: String = "{\"#uuid\":\"$id\"}",
	): PendingAdReport = PendingAdReport(id, identity, data)

	private fun peek(identity: AdReportIdentity = this.identity): List<PendingAdReport> =
		store.peek(identity, 100, AD_REPORT_MAX_BATCH_BYTES)
}

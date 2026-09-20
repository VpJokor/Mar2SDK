package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.AdError
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeConfig
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeInstance
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeMod
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProbeResult.Status
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader.AdapterSnapshot
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader.ErrorKind
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProxyReader.ResponseSnapshot
import com.mar2sdk.core.ad.impl.admob.probe.AdmobProxyAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdmobAdapterProxyReaderTest {
	private val winner = AdapterSnapshot("real.Adapter", "winner", "Real source", latencyMillis = 30)
	private val config = ProbeConfig(
		ProbeMod.ADAPTER_H, 3000, "USD",
		listOf(instance("50", 50.0), instance("30", 30.0), instance("20", 20.0), instance("12", 12.0)),
	)

	@Test
	fun bracketsWinnerUsingExecutedUpperProbesAndUnattemptedLowerRows() {
		val result = read(probe("50", true), probe("30", true), winner, probe("20"), probe("12"))

		assertEquals(Status.BOUNDED, result.status)
		assertEquals(30_000_000L, result.hPrice)
		assertEquals(20_000_000L, result.lPrice)
		assertEquals("30", result.hProbeInstanceId)
		assertEquals("20", result.lProbeInstanceId)
		assertEquals(listOf("50", "30"), result.executedProbeInstanceIds)
		assertEquals("response-1", result.responseId)
	}

	@Test
	fun keepsMissingSidesUnknownInsteadOfInventingZeroOrAnotherConfiguredTier() {
		val upper = read(probe("30", true), winner)
		assertEquals(Status.UPPER_BOUND_ONLY, upper.status)
		assertEquals(30_000_000L, upper.hPrice)
		assertNull(upper.lPrice)

		val lower = read(winner, probe("20"))
		assertEquals(Status.LOWER_BOUND_ONLY, lower.status)
		assertNull(lower.hPrice)
		assertEquals(20_000_000L, lower.lPrice)

		val noProbe = read(winner)
		assertEquals(Status.NO_PROBE, noProbe.status)
		assertNull(noProbe.hPrice)
		assertNull(noProbe.lPrice)
	}

	@Test
	fun missingConfiguredRowsNeverSupplyTheLowerBound() {
		val result = read(probe("50", true), winner, probe("12"))

		assertEquals(50_000_000L, result.hPrice)
		assertEquals(12_000_000L, result.lPrice)
	}

	@Test
	fun acceptsZeroLatencyOnlyWithTheDedicatedErrorAsExecutionEvidence() {
		assertEquals(30_000_000L, read(probe("30", true), winner).hPrice)
		assertUnknown(Status.ORDER_UNVERIFIED, read(probe("30"), winner))
		assertUnknown(Status.UNEXPECTED_PROBE_ERROR, read(probe("30").copy(error = ErrorKind.OTHER), winner))
	}

	@Test
	fun rejectsExecutedOrFailedProbeRowsAfterWinner() {
		for (row in listOf(
			probe("20", true),
			probe("20").copy(error = ErrorKind.OTHER),
			probe("20").copy(latencyMillis = 1),
			probe("20").copy(latencyMillis = -1),
		)) assertUnknown(Status.ORDER_UNVERIFIED, read(probe("30", true), winner, row))
	}

	@Test
	fun checksResponseOrderWithoutSortingItOrDependingOnConfigListOrder() {
		assertUnknown(Status.ORDER_UNVERIFIED, read(probe("30", true), probe("50", true), winner))
		assertUnknown(Status.ORDER_UNVERIFIED, read(probe("20", true), winner, probe("30")))
		assertUnknown(Status.ORDER_UNVERIFIED, read(winner, probe("12"), probe("20")))
		val response = snapshot(probe("30", true), winner, probe("20"))
		assertEquals(30_000_000L, AdmobAdapterProxyReader.readSnapshot(response, config.copy(instances = config.instances.reversed())).hPrice)
	}

	@Test
	fun acceptsEqualTiersAsInclusiveBounds() {
		val samePriceConfig = config.copy(instances = listOf(instance("30", 30.0), instance("20", 30.0)))
		val result = AdmobAdapterProxyReader.readSnapshot(snapshot(probe("30", true), winner, probe("20")), samePriceConfig)

		assertEquals(Status.BOUNDED, result.status)
		assertEquals(result.hPrice, result.lPrice)
	}

	@Test
	fun rejectsUnknownDuplicatedOrMisidentifiedProbeInstances() {
		val rows = listOf(
			probe("unknown", true),
			probe("30", true).copy(instanceName = "wrong"),
			probe("30", true).copy(adapterClassName = "other.Adapter"),
			probe("30", true).copy(customEventClassName = "other.Adapter"),
			probe("30", true).copy(parameter = "unexpected"),
			probe("30", true).copy(instanceId = "unknown", adapterClassName = "other.Adapter"),
		)
		for (row in rows) assertUnknown(Status.CONFIG_MISMATCH, read(row, winner))
		assertUnknown(Status.CONFIG_MISMATCH, read(probe("30", true), probe("30", true), winner))
	}

	@Test
	fun acceptsCustomEventWrapperOnlyWhenItsMappingIdentifiesThisAdapter() {
		val row = probe("30", true).copy(
			adapterClassName = "com.google.ads.mediation.customevent.CustomEventAdapter",
			customEventClassName = AdmobAdapterProxyReader.ADAPTER_CLASS_NAME,
		)
		assertEquals(30_000_000L, read(row, winner).hPrice)
		assertUnknown(Status.CONFIG_MISMATCH, read(row.copy(customEventClassName = null), winner))
	}

	@Test
	fun verifiesConfiguredParameterOnBothSidesOfTheWinner() {
		val withParameters = config.copy(instances = config.instances.map { it.copy(param = "v1:${it.instanceId}") })
		val upper = probe("30", true).copy(parameter = "v1:30")
		val lower = probe("20").copy(parameter = "v1:20")
		assertEquals(Status.BOUNDED, AdmobAdapterProxyReader.readSnapshot(snapshot(upper, winner, lower), withParameters).status)
		for (rows in listOf(
			arrayOf(upper.copy(parameter = null), winner, lower),
			arrayOf(upper, winner, lower.copy(parameter = "v2:20")),
		)) assertUnknown(Status.CONFIG_MISMATCH, AdmobAdapterProxyReader.readSnapshot(snapshot(*rows), withParameters))
	}

	@Test
	fun requiresASuccessfulUniqueWinnerInTheSameResponse() {
		val valid = snapshot(probe("30", true), winner, probe("20"))
		assertUnknown(Status.MISSING_RESPONSE, AdmobAdapterProxyReader.readSnapshot(null, config))
		for (response in listOf(
			valid.copy(winner = null),
			valid.copy(winner = winner.copy(instanceId = "missing")),
			valid.copy(winner = winner.copy(instanceId = "")),
			valid.copy(winner = winner.copy(error = ErrorKind.OTHER)),
			valid.copy(winner = winner.copy(adapterClassName = "different.Adapter")),
			valid.copy(adapters = listOf(winner, winner)),
			valid.copy(adapters = listOf(winner.copy(error = ErrorKind.OTHER))),
		)) assertUnknown(Status.MISSING_WINNER, AdmobAdapterProxyReader.readSnapshot(response, config))
		assertUnknown(Status.PROBE_LOADED_UNEXPECTEDLY, AdmobAdapterProxyReader.readSnapshot(valid.copy(winner = probe("30")), config))
	}

	@Test
	fun ignoresUnrelatedNetworkFailuresAndUnattemptedRows() {
		val failedNetwork = AdapterSnapshot("network.Adapter", "network", "Network", error = ErrorKind.OTHER)
		val unattemptedNetwork = failedNetwork.copy(instanceId = "unused", error = ErrorKind.NONE)
		val result = read(failedNetwork, probe("30", true), winner, unattemptedNetwork, probe("20"))
		assertEquals(Status.BOUNDED, result.status)
	}

	@Test
	fun convertsDecimalEcpmExactlyAndRejectsInvalidAmounts() {
		val response = snapshot(probe("30", true), winner)
		for ((ecpm, micros) in listOf(15.5 to 15_500_000L, 0.000001 to 1L, 0.1 to 100_000L)) {
			val decimalConfig = config.copy(instances = listOf(instance("30", ecpm)))
			assertEquals(micros, AdmobAdapterProxyReader.readSnapshot(response, decimalConfig).hPrice)
		}
		for (ecpm in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 0.0000001, 1.0000001)) {
			val invalid = config.copy(instances = listOf(instance("30", ecpm)))
			assertUnknown(Status.INVALID_CONFIG, AdmobAdapterProxyReader.readSnapshot(response, invalid))
		}
	}

	@Test
	fun rejectsAmbiguousConfigAndNonUsdPrices() {
		for (instances in listOf(
			listOf(instance("30", 30.0), instance("30", 20.0)),
			listOf(instance("30", 30.0), instance("20", 20.0).copy(label = "Probe_30")),
			listOf(instance("", 30.0)),
			listOf(instance("30", 30.0).copy(label = "")),
		)) assertUnknown(Status.INVALID_CONFIG, AdmobAdapterProxyReader.readSnapshot(snapshot(winner), config.copy(instances = instances)))
		assertUnknown(Status.CURRENCY_MISMATCH, AdmobAdapterProxyReader.readSnapshot(snapshot(winner), config.copy(currency = "EUR")))
	}

	@Test
	fun resultsAreIndependentAndRetainTheirOwnConfigSnapshot() {
		val mutableInstances = config.instances.toMutableList()
		val first = AdmobAdapterProxyReader.readSnapshot(snapshot(probe("30", true), winner, probe("20")), config.copy(instances = mutableInstances))
		mutableInstances.clear()
		val second = read(probe("50", true), winner, probe("12"))
		assertEquals(4, first.config.instances.size)
		assertEquals(30_000_000L, first.hPrice)
		assertEquals(20_000_000L, first.lPrice)
		assertEquals(50_000_000L, second.hPrice)
		assertEquals(12_000_000L, second.lPrice)
	}

	@Test
	fun inferenceIsIndependentOfTheSelectedPriceMode() {
		for (mode in ProbeMod.entries) {
			val result = AdmobAdapterProxyReader.readSnapshot(snapshot(probe("30", true), winner, probe("20")), config.copy(mod = mode))
			assertEquals(Status.BOUNDED, result.status)
		}
	}

	@Test
	fun recognizesOnlyTheDedicatedDomainAndCodeIncludingWrappedErrors() {
		val marker = AdError(AdmobProxyAdapter.PROBE_NO_FILL_CODE, "any message", AdmobProxyAdapter.PROBE_ERROR_DOMAIN)
		assertEquals(ErrorKind.NONE, AdmobAdapterProxyReader.errorKind(null))
		assertEquals(ErrorKind.PROBE_NO_FILL, AdmobAdapterProxyReader.errorKind(marker))
		assertEquals(ErrorKind.PROBE_NO_FILL, AdmobAdapterProxyReader.errorKind(AdError(3, "wrapper", "com.google.android.gms.ads", marker)))
		assertEquals(ErrorKind.OTHER, AdmobAdapterProxyReader.errorKind(AdError(3, marker.message, marker.domain)))
		assertEquals(ErrorKind.OTHER, AdmobAdapterProxyReader.errorKind(AdError(marker.code, marker.message, "com.google.android.gms.ads")))
		var tooDeep = marker
		repeat(8) { tooDeep = AdError(3, "wrapper", "com.google.android.gms.ads", tooDeep) }
		assertEquals(ErrorKind.OTHER, AdmobAdapterProxyReader.errorKind(tooDeep))
	}

	private fun instance(id: String, ecpm: Double) = ProbeInstance(id, "Probe_$id", ecpm, "")
	private fun probe(id: String, fired: Boolean = false) = AdapterSnapshot(
		AdmobAdapterProxyReader.ADAPTER_CLASS_NAME, id, "Probe_$id",
		error = if (fired) ErrorKind.PROBE_NO_FILL else ErrorKind.NONE,
	)
	private fun snapshot(vararg rows: AdapterSnapshot) = ResponseSnapshot("response-1", winner, rows.toList())
	private fun read(vararg rows: AdapterSnapshot) = AdmobAdapterProxyReader.readSnapshot(snapshot(*rows), config)
	private fun assertUnknown(status: Status, result: com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProbeResult) {
		assertEquals(status, result.status)
		assertNull(result.hPrice)
		assertNull(result.lPrice)
	}
}

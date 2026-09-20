package com.mar2sdk.core.ad.impl.admob.probe

import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdapterResponseInfo
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.mediation.MediationConfiguration
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeConfig
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeInstance
import com.mar2sdk.core.ad.impl.admob.probe.AdmobAdapterProbeResult.Status
import java.math.BigDecimal

/**
 * 从一次成功加载的响应读取排序区间，不发起请求，也不保存跨请求的状态。
 *
 * H 为胜出项之前确认主动 no-fill 的探针最低价；L 为本次响应中位于胜出项
 * 之后、未执行且通过配置核验的探针最高价。不会用配置中未出现在响应里的
 * 档位补出 L，也不会把没有错误的条目当成主动 no-fill 的执行证据。
 *
 * 必须保留 ResponseInfo 的原始顺序。上下界均以后台与配置价格一致且同口径
 * 降序排序为前提；公开响应不能核实后台实际 Manual eCPM 或真实结算收益。
 */
object AdmobAdapterProxyReader {
	internal const val ADAPTER_CLASS_NAME = "com.mar2sdk.core.ad.impl.admob.probe.AdmobProxyAdapter"
	private const val CUSTOM_EVENT_CLASS_FIELD = "class_name"
	private const val MAX_ERROR_DEPTH = 8

	/** config 应在实际发起 SDK 加载之前冻结，不能传入加载完成时的新配置。 */
	fun read(responseInfo: ResponseInfo?, config: ProbeConfig): AdmobAdapterProbeResult {
		val frozenConfig = config.copy(instances = config.instances.toList())
		return try {
			readSnapshot(responseInfo?.let { response ->
				ResponseSnapshot(
					responseId = response.responseId,
					winner = response.loadedAdapterResponseInfo?.toSnapshot(),
					adapters = response.adapterResponses.map { it.toSnapshot() },
				)
			}, frozenConfig)
		} catch (_: Exception) {
			AdmobAdapterProbeResult(Status.READ_FAILED, frozenConfig)
		} catch (_: LinkageError) {
			AdmobAdapterProbeResult(Status.READ_FAILED, frozenConfig)
		}
	}

	internal fun readSnapshot(response: ResponseSnapshot?, config: ProbeConfig): AdmobAdapterProbeResult {
		val frozenConfig = config.copy(instances = config.instances.toList())
		fun unknown(status: Status) = AdmobAdapterProbeResult(status, frozenConfig, response?.responseId)
		if (config.currency != "USD") return unknown(Status.CURRENCY_MISMATCH)
		if (config.instances.isEmpty()) return unknown(Status.NO_PROBE)
		val prices = validatedPrices(config.instances) ?: return unknown(Status.INVALID_CONFIG)
		if (response == null) return unknown(Status.MISSING_RESPONSE)
		val winner = response.winner ?: return unknown(Status.MISSING_WINNER)
		if (winner.isProbe || winner.instanceId in prices) return unknown(Status.PROBE_LOADED_UNEXPECTEDLY)
		val winnerIndices = response.adapters.indices.filter { response.adapters[it].instanceId == winner.instanceId }
		if (winner.instanceId.isBlank() || winnerIndices.size != 1) return unknown(Status.MISSING_WINNER)
		val winnerIndex = winnerIndices.single()
		val winnerRow = response.adapters[winnerIndex]
		if (winner.error != ErrorKind.NONE || winnerRow.error != ErrorKind.NONE ||
			winner.adapterClassName != winnerRow.adapterClassName || winner.instanceName != winnerRow.instanceName
		) return unknown(Status.MISSING_WINNER)

		val instances = config.instances.associateBy { it.instanceId }
		val labels = config.instances.map { it.label }.toSet()
		val seen = mutableSetOf<String>()
		val executed = mutableListOf<String>()
		var previousPrice: Long? = null
		var hPrice: Long? = null
		var lPrice: Long? = null
		var hInstanceId: String? = null
		var lInstanceId: String? = null
		for ((index, row) in response.adapters.withIndex()) {
			val instance = instances[row.instanceId]
			if (instance == null) {
				if (row.isProbe || row.error == ErrorKind.PROBE_NO_FILL || row.instanceName in labels) {
					return unknown(Status.CONFIG_MISMATCH)
				}
				continue
			}
			if (!seen.add(row.instanceId) || !row.isProbe || row.instanceName != instance.label ||
				row.parameter.orEmpty() != instance.param ||
				(row.customEventClassName != null && row.customEventClassName != ADAPTER_CLASS_NAME)
			) return unknown(Status.CONFIG_MISMATCH)
			val price = prices.getValue(row.instanceId)
			if (previousPrice != null && price > previousPrice) return unknown(Status.ORDER_UNVERIFIED)
			previousPrice = price
			if (row.latencyMillis < 0) return unknown(Status.ORDER_UNVERIFIED)
			if (index < winnerIndex) {
				when (row.error) {
					ErrorKind.NONE -> return unknown(Status.ORDER_UNVERIFIED)
					ErrorKind.OTHER -> return unknown(Status.UNEXPECTED_PROBE_ERROR)
					ErrorKind.PROBE_NO_FILL -> {
						executed += row.instanceId
						if (hPrice == null || price < hPrice) {
							hPrice = price
							hInstanceId = row.instanceId
						}
					}
				}
			} else {
				if (row.error != ErrorKind.NONE || row.latencyMillis != 0L) return unknown(Status.ORDER_UNVERIFIED)
				if (lPrice == null || price > lPrice) {
					lPrice = price
					lInstanceId = row.instanceId
				}
			}
		}
		val status = when {
			hPrice != null && lPrice != null -> Status.BOUNDED
			hPrice != null -> Status.UPPER_BOUND_ONLY
			lPrice != null -> Status.LOWER_BOUND_ONLY
			else -> Status.NO_PROBE
		}
		return AdmobAdapterProbeResult(
			status, frozenConfig, response.responseId, hPrice, lPrice, hInstanceId, lInstanceId, executed.toList(),
		)
	}

	private fun validatedPrices(instances: List<ProbeInstance>): Map<String, Long>? {
		val prices = mutableMapOf<String, Long>()
		val labels = mutableSetOf<String>()
		for (instance in instances) {
			if (instance.instanceId.isBlank() || instance.label.isBlank() ||
				instance.instanceId in prices || !labels.add(instance.label) ||
				!instance.ecpm.isFinite() || instance.ecpm <= 0
			) return null
			val micros = try {
				BigDecimal.valueOf(instance.ecpm).movePointRight(6).longValueExact()
			} catch (_: ArithmeticException) {
				return null
			}
			prices[instance.instanceId] = micros
		}
		return prices
	}

	private fun AdapterResponseInfo.toSnapshot() = AdapterSnapshot(
		adapterClassName = adapterClassName,
		instanceId = adSourceInstanceId,
		instanceName = adSourceInstanceName,
		parameter = credentials.getString(MediationConfiguration.CUSTOM_EVENT_SERVER_PARAMETER_FIELD),
		customEventClassName = credentials.getString(CUSTOM_EVENT_CLASS_FIELD),
		latencyMillis = latencyMillis,
		error = errorKind(adError),
	)

	internal fun errorKind(error: AdError?): ErrorKind {
		if (error == null) return ErrorKind.NONE
		var current: AdError? = error
		repeat(MAX_ERROR_DEPTH) {
			val item = current ?: return ErrorKind.OTHER
			if (item.domain == AdmobProxyAdapter.PROBE_ERROR_DOMAIN && item.code == AdmobProxyAdapter.PROBE_NO_FILL_CODE) {
				return ErrorKind.PROBE_NO_FILL
			}
			current = item.cause
		}
		return ErrorKind.OTHER
	}

	internal enum class ErrorKind { NONE, PROBE_NO_FILL, OTHER }

	internal data class ResponseSnapshot(
		val responseId: String?,
		val winner: AdapterSnapshot?,
		val adapters: List<AdapterSnapshot>,
	)

	internal data class AdapterSnapshot(
		val adapterClassName: String,
		val instanceId: String,
		val instanceName: String,
		val parameter: String? = null,
		val customEventClassName: String? = null,
		val latencyMillis: Long = 0,
		val error: ErrorKind = ErrorKind.NONE,
	) {
		val isProbe: Boolean get() = adapterClassName == ADAPTER_CLASS_NAME || customEventClassName == ADAPTER_CLASS_NAME
	}
}

package com.mar2sdk.core.ad.impl.admob.probe

import com.mar2sdk.core.ad.impl.admob.ProbeConfig

/**
 * 一次成功加载的广告在瀑布排序中的推断区间，单位为 USD eCPM 微单位。
 *
 * 依赖后台 Manual eCPM 与 [config] 一致、真实来源与探针按相同口径降序排列。
 * H/L 是排序估值的上/下界，不是实际展示收益；缺少一侧证据时该侧为 null。
 */
@ConsistentCopyVisibility
data class AdmobAdapterProbeResult internal constructor(
	val status: Status,
	val config: ProbeConfig,
	val responseId: String? = null,
	val hPrice: Long? = null,
	val lPrice: Long? = null,
	val hProbeInstanceId: String? = null,
	val lProbeInstanceId: String? = null,
	val executedProbeInstanceIds: List<String> = emptyList(),
) {
	enum class Status {
		BOUNDED,
		UPPER_BOUND_ONLY,
		LOWER_BOUND_ONLY,
		NO_PROBE,
		MISSING_RESPONSE,
		MISSING_WINNER,
		INVALID_CONFIG,
		CURRENCY_MISMATCH,
		CONFIG_MISMATCH,
		ORDER_UNVERIFIED,
		UNEXPECTED_PROBE_ERROR,
		PROBE_LOADED_UNEXPECTEDLY,
		READ_FAILED,
	}

}

package com.mar2sdk.core.ad.impl.admob

import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeConfig
import com.mar2sdk.core.ad.impl.admob.AdmobConfig.ProbeMod
import com.mar2sdk.core.ad.impl.admob.probe.AdmobPrice

/** 展示选择统一使用 USD eCPM 微单位；缺少当前模式所需价格时返回 null。 */
internal fun resolveComparisonPriceEcpmMicros(
	config: ProbeConfig,
	reflectPrice: AdmobPrice?,
	hPrice: Long?,
	lPrice: Long?,
): Long? {
	if (config.mod == ProbeMod.REFLECT) {
		return reflectPrice?.takeIf {
			it.currencyCode == "USD" && it.valueMicros in 1L..(Long.MAX_VALUE / 1_000L)
		}?.ecpmMicros
	}
	if (config.currency != "USD" ||
		(hPrice != null && hPrice <= 0L) || (lPrice != null && lPrice <= 0L) ||
		(hPrice != null && lPrice != null && hPrice < lPrice)
	) return null

	return when (config.mod) {
		ProbeMod.ADAPTER_H -> hPrice
		ProbeMod.ADAPTER_L -> lPrice
		ProbeMod.ADAPTER_M -> {
			if (hPrice == null || lPrice == null) null
			// 避免上下界相加溢出；不足一个微单位的部分向下取整。
			else lPrice + (hPrice - lPrice) / 2L
		}
		ProbeMod.REFLECT -> null // 已在上方处理。
	}
}

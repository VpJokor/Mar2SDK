package com.mar2sdk.core.ad.policy

import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.log.LogAdEvent
import com.mar2sdk.core.log.LogAdParam
import com.mar2sdk.core.util.DBUtil
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object AdPolicy {

	fun canShowAd(adContext: ScreenAdContext): Boolean {
		if (!AdConfig.isOpen) return false
		if (!isShowMax()) return false
		return isShow(adContext)
	}

	// 判断全局每小时和每日展示上限是否允许展示广告。
	fun isShowMax(): Boolean {
		val now = System.currentTimeMillis()
		val logs = queryAdImpressionLogs()
		val showCount1H = logs.count { it.eventTimeMillis in (now - HOUR_MILLIS)..now }
		val showCount24H = logs.count { it.eventTimeMillis in (now - DAY_MILLIS)..now }
		return showCount1H < AdConfig.max1H && showCount24H < AdConfig.max24H
	}

	// 判断广告点位配置是否允许展示广告。
	fun isShow(adContext: ScreenAdContext): Boolean {
		val config = AdConfig.adUnits[adContext.areaKey] ?: return false
		val now = System.currentTimeMillis()
		val areaLogs = queryAdImpressionLogs().filter { log ->
			try {
				JSONObject(log.paramsJson).optString(LogAdParam.ad_areakey) == adContext.areaKey
			} catch (_: Exception) {
				false
			}
		}
		val showCount1H = areaLogs.count { it.eventTimeMillis in (now - HOUR_MILLIS)..now }
		val showCount24H = areaLogs.count { it.eventTimeMillis in (now - DAY_MILLIS)..now }
		if (showCount1H >= config.max1H || showCount24H >= config.max24H) return false

		val lastShowTime = areaLogs
			.filter { it.eventTimeMillis <= now }
			.maxOfOrNull { it.eventTimeMillis } ?: 0L
		if (lastShowTime > 0L && now - lastShowTime < config.interval * 1000L) return false
		if (!config.fromRoutes.contains(adContext.fromRoute) && !config.fromRoutes.contains("*")) return false
		if (!config.toRoutes.contains(adContext.toRoute) && !config.toRoutes.contains("*")) return false
		// rate 表示广告展示概率，1.0 表示始终展示。
		if (Math.random() >= config.rate) return false
		adContext.adFormat = config.format
		return true
	}

	// 分页读取本地展示日志，因为 DBUtil 单次查询最多返回 500 条。
	private fun queryAdImpressionLogs(): List<DBUtil.LocalLog> = runBlocking {
		val result = mutableListOf<DBUtil.LocalLog>()
		var beforeId = Long.MAX_VALUE
		while (true) {
			val logs = DBUtil.queryLogs(limit = QUERY_PAGE_SIZE, beforeId = beforeId)
			if (logs.isEmpty()) break
			result += logs.filter { it.eventName == LogAdEvent.ad_impression }
			beforeId = logs.last().id
		}
		result
	}

	private const val QUERY_PAGE_SIZE = 500
	private const val HOUR_MILLIS = 60 * 60 * 1000L
	private const val DAY_MILLIS = 24 * HOUR_MILLIS
}

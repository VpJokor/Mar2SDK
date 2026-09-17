package com.mar2sdk.core.log

// 日志上报渠道配置对应的 SharedPreferences 键。
object LogKey {
	const val KEY_FB_EVENTS = "mar2sdk.log_config.fbEvents"
	const val KEY_LOCAL_EVENTS = "mar2sdk.log_config.localEvents"
	const val KEY_TH_EVENTS = "mar2sdk.log_config.thEvents"
	const val KEY_NET_EVENTS = "mar2sdk.log_config.netEvents"
	const val KEY_REPORT_BATCH_SIZE = "mar2sdk.log_config.reportBatchSize"
	const val KEY_REPORT_FLUSH_INTERVAL_MILLIS = "mar2sdk.log_config.reportFlushIntervalMillis"
}

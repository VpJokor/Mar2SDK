package com.mar2sdk.core.ad.status

// OPEN_INTER：优先开屏，候补插屏
// INTER_VIDEO：优先插屏，候补视频
// VIDEO_INTER：视频与插屏比价，同价或缺少有效价格时视频优先
enum class AdFormat {
	OPEN,
	INTER,
	VIDEO,
	OPEN_INTER,
	INTER_VIDEO,
	VIDEO_INTER,
}

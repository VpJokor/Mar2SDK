package com.mar2sdk.core


/**
 * APP状态管理类
 */
object AppStatus {

	// 屏幕状态 亮屏/熄屏
	var isScreenOn = false
	// 手机状态 锁屏/解锁
	var isLocked = false

	// 广告状态 (是/否)正在展示全屏广告
	var isShowingAd = false
	// 广告状态 正在展示全屏广告的类型(空/开屏/插屏/视频)-- (null/open/inter/video)
	var showingAdType = "null"
	// 广告状态 上次展示广告的时间
	var lastShowingAdTime = 0L

	// 通知状态 (是/否正在发送通知)
	var isNotifying = false
	// 通知状态 上次发送通知的时间
	var lastNotifyTime = 0L


}
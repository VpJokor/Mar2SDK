package com.mar2sdk.core

import android.util.Log

/**
 * APP状态管理类
 */
object AppStatus {
	private const val TAG = "AppStatus"
	// 屏幕状态 亮屏/熄屏
	@Volatile
	var isScreenOn = false
	// 手机状态 锁屏/解锁
	@Volatile
	var isLocked = false
	// APP是否在前台
	@Volatile
	var isForeground = false

	// 广告状态 (是/否)正在展示全屏广告
	var isShowingAd = false

	val listener = AppObs.Listener{ event ->
		Log.e(TAG, "AppStatus change: $event" )
	}

	// 通知状态 (是/否正在发送通知)
	var isNotifying = false
	// 通知状态 上次发送通知的时间
	var lastNotifyTime = 0L


}

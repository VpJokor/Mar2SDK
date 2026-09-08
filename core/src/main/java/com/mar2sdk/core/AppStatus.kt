package com.mar2sdk.core

import android.util.Log
import com.mar2sdk.core.ad.AdLoader
import com.mar2sdk.core.util.AppObs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * APP状态管理类
 */
object AppStatus {
	private const val TAG = "AppStatus"
	private val adPreloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var adPreloadJob: Job? = null
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
		when(event) {
			// INFO: 前后台切换
			is AppObs.Event.ForegroundChanged -> {
				if (event.isForeground) {
					// INFO: 开屏填充广告池
					startAdPreload()
				} else {

				}
			}
			// INFO: HOME键
			is AppObs.Event.HomePressed -> {

			}
			// INFO: RECENT键
			is AppObs.Event.RecentAppsPressed -> {

			}
			// INFO: 亮屏熄屏
			is AppObs.Event.ScreenChanged -> {

			}
			// INFO: 安装/卸载/更新
			is AppObs.Event.PackageChanged -> {

			}
			// INFO: 媒体库(相册/文档/音乐/下载)
			is AppObs.Event.MediaChanged -> {

			}
			// INFO: 电量
			is AppObs.Event.PowerChanged -> {

			}
			// INFO: 音量
			is AppObs.Event.VolumeChanged -> {

			}
			// INFO: USB
			is AppObs.Event.UsbChanged -> {

			}
			// INFO: WIFI
			is AppObs.Event.WifiChanged -> {

			}
			// INFO: 网络
			is AppObs.Event.NetworkChanged -> {

			}
		}

	}


	private fun startAdPreload() {
		if (adPreloadJob?.isActive == true) return
		adPreloadJob = adPreloadScope.launch {
			try {
				AdLoader.fillAd()
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				Log.e(TAG, "Failed to preload ads when app entered foreground", exception)
			}
		}
	}

}

package com.mar2sdk.core.notify.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log

// 开机启动常驻通知
class BootReceiver : BroadcastReceiver() {
	companion object {
		private const val TAG = "BootReceiver"
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
			Log.e(TAG, "onReceive: 启动完成")
			Handler(context.mainLooper).postDelayed({
				try {
					CommonService.start(context)
				} catch (e: Exception) {
					Log.e(TAG, "onReceive: 启动服务失败", e)
				}
			}, 2000)
		}
	}

}

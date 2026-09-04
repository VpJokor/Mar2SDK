package com.mar2sdk.core.notify.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 通知删除广播接收器，用户移除持久通知后尝试重新拉起服务。 */
class CommonDelReceiver : BroadcastReceiver() {
	companion object {
		const val TAG = "CommonDelReceiver"
	}

	/** 收到删除事件后按配置启动 [CommonService]。 */
	override fun onReceive(context: Context?, intent: Intent?) {
		Log.e(TAG, "onReceive: " )
		context?.let(CommonService::start)
	}

}

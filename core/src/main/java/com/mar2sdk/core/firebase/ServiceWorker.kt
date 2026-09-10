package com.mar2sdk.core.firebase

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mar2sdk.core.notify.NotificationUtil

/** 通过 WorkManager 拉起持久前台服务的兜底任务。 */
class ServiceWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

	/** 执行一次服务启动；成功返回 success，启动失败返回 failure。 */
	override fun doWork(): Result {
		try {
			NotificationUtil.startFGS()
			return Result.success()
		} catch (e: Exception) {
			Log.e("ServiceWorker", "startForegroundService failed with ${e.javaClass.simpleName}")
			return Result.failure()
		}
	}
}

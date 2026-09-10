package com.mar2sdk.core.firebase

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import androidx.core.content.ContextCompat
import com.mar2sdk.core.notify.NotificationUtil

// 通过 JobScheduler 延迟拉起持久前台服务的兜底任务。 */
@SuppressLint("SpecifyJobSchedulerIdRange")
class ServiceStarterJobService : JobService() {
	/** Job 到期后尝试启动 CommonService 或宿主自定义服务。 */
	override fun onStartJob(params: JobParameters?): Boolean {

		// 这里的代码会在 10s 后执行
		try {
			NotificationUtil.startFGS()
		} catch (e: Exception) {
			Log.e("ServiceStarterJob", "startForegroundService failed with ${e.javaClass.simpleName}")
		}

		// 返回 false 表示任务执行完毕
		return false
	}

	/** 任务被系统中止时允许重试。 */
	override fun onStopJob(params: JobParameters?): Boolean {
		// 如果任务被系统意外终止，返回 true 表示愿意再次尝试
		return true
	}
}

package com.mar2sdk.core.notify

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import com.mar2sdk.core.Core
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogNotifyParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.notify.common.CommonService

object NotificationUtil {
	private const val EXTRA_APP_OPEN_FROM = "AppOpenFrom"
	private const val EXTRA_ROUTE = "Route"
	private const val EXTRA_SCENE = "Scene"
	private const val EXTRA_CLICK_TRACKED = "Mar2SDK.NotificationClickTracked"
	private const val SOURCE_APP_PUSH = "app_push"
	private const val SOURCE_PERSISTENT = "persistent"

	// 请求通知权限
	fun reqNotiAccess(
		activity: Activity,
		launcher: ActivityResultLauncher<String>,
		onResult: (Boolean) -> Unit
	) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			onResult(NotificationManagerCompat.from(activity).areNotificationsEnabled())
			return
		}

		if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
			PackageManager.PERMISSION_GRANTED
		) {
			onResult(true)
		} else {
			launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}

	// 判断是否有通知权限
	fun hasNotiAccess() : Boolean {
		val context = Core.app
		if (
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		) {
			return false
		}
		return NotificationManagerCompat.from(context).areNotificationsEnabled()
	}

	// 启动前台服务
	fun startFGS() {
		CommonService.start(Core.app)
	}

	// 当通知的 PendingIntent 打开宿主应用时，记录通知点击事件。
	internal fun trackNotificationClick(intent: Intent?) {
		if (intent?.getBooleanExtra(EXTRA_CLICK_TRACKED, false) == true) return
		val source = intent?.getStringExtra(EXTRA_APP_OPEN_FROM) ?: return
		if (source != SOURCE_APP_PUSH && source != SOURCE_PERSISTENT) return

		val params = mutableMapOf<String, Any>(LogNotifyParam.source to source)
		intent.getStringExtra(EXTRA_SCENE)?.let { params[LogNotifyParam.scene] = it }
		intent.getStringExtra(EXTRA_ROUTE)?.let { params[LogNotifyParam.route] = it }
		LogUtil.log(LogNotifyEvent.notification_clicked, params)
		intent.putExtra(EXTRA_CLICK_TRACKED, true)
	}

}

package com.mar2sdk.core.notify

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat

object NotificationUtil {

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


	// TODO: 判断是否有通知权限
	fun hasNotiAccess() : Boolean {

		return false
	}
}

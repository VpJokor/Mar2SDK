package com.mar2sdk.core.notify

import android.app.Activity

object NotificationUtil {

	// 请求通知权限
	fun reqNotiAccess(activity: Activity) {
		if (
			android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
			activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
			android.content.pm.PackageManager.PERMISSION_GRANTED
		) {
			activity.requestPermissions(
				arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
				1001
			)
		}
	}
}
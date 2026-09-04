package com.mar2sdk.core.notify

object NotificationConfig {

	// 是否有前台服务权限
	var hasForegroundServiceAccess = false

	// APP通知的通道数
	var ChannelCount = 3

	// 通知文案
	var contents = listOf(NotificationContent(listOf(),"Title", "Content", "Button", "", "Route"))

	fun saveNotificationConfig() {
		// TODO:
	}
}
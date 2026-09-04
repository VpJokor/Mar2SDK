package com.mar2sdk.core.notify

object NotificationConfig {

	// APP通知的通道数
	var ChannelCount = 3

	// 通知文案
	var contents = listOf(NotificationContent(listOf(),"Title", "Content", "Button", "", "Route"))

	fun saveNotificationConfig() {
		// TODO:
	}
}
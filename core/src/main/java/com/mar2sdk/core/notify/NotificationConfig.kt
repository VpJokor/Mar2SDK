package com.mar2sdk.core.notify

object NotificationConfig {
	// APP通知的通道数
	var ChannelCount = 3

	/**
	 * 通知内容配置，对应 Remote Config 的 notification_content 数组元素。
	 *
	 * @property Title 默认通知标题。
	 * @property Content 默认通知正文。
	 * @property Button 默认按钮文案。
	 * @property Languages 多语言 JSON 字符串，后续按 Languages 结构解析。
	 * @property Route 通知点击原始路由。
	 */
	var contents = listOf(NotificationContent(listOf(),"Title", "Content", "Button", "", "Route"))
}
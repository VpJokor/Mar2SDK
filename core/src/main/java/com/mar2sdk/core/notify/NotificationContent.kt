package com.mar2sdk.core.notify

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json



/**
 * 通知内容配置，对应 Remote Config 的 notification_content 数组元素。
 *
 * @property Title 默认通知标题。
 * @property Content 默认通知正文。
 * @property Button 默认按钮文案。
 * @property Languages 多语言 JSON 字符串，后续按 Languages 结构解析。
 * @property Route 通知点击原始路由。
 */

@Serializable
data class NotificationContent(
	val Scenes: List<String>,
	val Title: String,
	val Content: String,
	val Button: String,
	val Languages: String, // 这里先用 String，后续按需再解析成 Languages。
	val Route: String
)

/**
 * 通知多语言内容。
 *
 * @property language 语言码，例如 zh、en、ja、ko。
 * @property title 对应语言的通知标题。
 * @property content 对应语言的通知正文。
 * @property button 对应语言的按钮文案。
 */
@Serializable
data class LanguageKey(
	val language: String,
	val title: String,
	val content: String,
	val img: String = "",
	val button: String
)

/**
 * 多语言内容根结构。
 *
 * @property keys 多语言条目列表。
 */
@Serializable
data class Languages(
	val keys: List<LanguageKey>
)

private val notificationJson = Json {
	ignoreUnknownKeys = true
	explicitNulls = false
	coerceInputValues = true
}

/** 解析通知触发策略 JSON。 */
fun parseNotificationConfig(jsonString: String): NotificationConfig {
	return notificationJson.decodeFromString(jsonString)
}

/** 解析通知内容列表 JSON。 */
fun parseNotificationContents(jsonString: String): List<NotificationContent> {
	return notificationJson.decodeFromString(jsonString)
}

/** 解析单条通知的多语言内容 JSON。 */
fun parseLanguages(jsonString: String): Languages {
	return notificationJson.decodeFromString(jsonString)
}

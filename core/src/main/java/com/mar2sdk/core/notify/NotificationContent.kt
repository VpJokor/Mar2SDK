package com.mar2sdk.core.notify

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject



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

/** 解析通知触发策略 JSON。 */
fun parseNotificationConfig(jsonString: String): NotificationConfig {
	NotificationConfig.applyConfig(JSONObject(jsonString))
	return NotificationConfig
}

/** 解析通知内容列表 JSON。 */
fun parseNotificationContents(jsonString: String): List<NotificationContent> {
	val contents = JSONArray(jsonString)
	return (0 until contents.length()).map { index ->
		contents.getJSONObject(index).toNotificationContent()
	}
}

/** 将通知内容列表编码为可保存到配置中的 JSON 字符串。 */
fun serializeNotificationContents(contents: List<NotificationContent>): String {
	val array = JSONArray()
	contents.forEach { content ->
		array.put(JSONObject().apply {
			put("Scenes", JSONArray().apply { content.Scenes.forEach(::put) })
			put("Title", content.Title)
			put("Content", content.Content)
			put("Button", content.Button)
			put("Languages", content.Languages)
			put("Route", content.Route)
		})
	}
	return array.toString()
}

/** 解析单条通知的多语言内容 JSON。 */
fun parseLanguages(jsonString: String): Languages {
	val keys = JSONObject(jsonString).optJSONArray("keys") ?: JSONArray()
	return Languages(
		(0 until keys.length()).map { index ->
			val key = keys.getJSONObject(index)
			LanguageKey(
				language = key.optString("language"),
				title = key.optString("title"),
				content = key.optString("content"),
				img = key.optString("img"),
				button = key.optString("button")
			)
		}
	)
}

private fun JSONObject.toNotificationContent(): NotificationContent {
	val scenes = optJSONArray("Scenes") ?: JSONArray()
	return NotificationContent(
		Scenes = (0 until scenes.length()).map { scenes.optString(it) },
		Title = optString("Title"),
		Content = optString("Content"),
		Button = optString("Button"),
		Languages = when (val languages = opt("Languages")) {
			is JSONObject, is JSONArray -> languages.toString()
			else -> optString("Languages")
		},
		Route = optString("Route")
	)
}

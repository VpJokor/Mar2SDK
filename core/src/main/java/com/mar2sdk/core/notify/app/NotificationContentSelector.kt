package com.mar2sdk.core.notify.app

import com.mar2sdk.core.notify.NotificationContent
import java.util.Locale
import kotlin.random.Random

internal object NotificationContentSelector {

	fun select(
		contents: List<NotificationContent>,
		scene: String,
		locale: Locale,
		random: Random = Random.Default
	): NotificationContent? {
		val content = contents.filter { scene in it.Scenes || "*" in it.Scenes }
			.randomOrNull(random) ?: return null

		// 优先完整语言标签，再回退到文字、地区和基础语言，如 zh-Hant-TW -> zh-Hant -> zh-TW -> zh。
		val languageTags = buildList {
			add(locale.toLanguageTag())
			if (locale.script.isNotEmpty()) add("${locale.language}-${locale.script}")
			if (locale.country.isNotEmpty()) add("${locale.language}-${locale.country}")
			add(locale.language)
		}
		val translation = languageTags.firstNotNullOfOrNull { tag ->
			content.Languages.entries.firstOrNull { (language, _) ->
				language.replace('_', '-').equals(tag, ignoreCase = true)
			}?.value
		} ?: return content

		return content.copy(
			Title = translation.title.ifBlank { content.Title },
			Content = translation.content.ifBlank { content.Content },
			Button = translation.button.ifBlank { content.Button }
		)
	}
}

package com.mar2sdk.core.notify.app

import com.mar2sdk.core.notify.NotificationContent
import com.mar2sdk.core.notify.NotificationLanguage
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContentSelectorTest {

	@Test
	fun selectsRandomlyFromExactSceneAndWildcardMatches() {
		val contents = listOf(
			content().copy(Title = "exact"),
			content().copy(Title = "wildcard", Scenes = listOf("*")),
			content().copy(Title = "another scene", Scenes = listOf("other")),
			content().copy(Title = "different case", Scenes = listOf("Launch")),
			content().copy(Title = "no scenes", Scenes = emptyList())
		)
		val random = Random(1234)

		val selected = (1..100).map {
			NotificationContentSelector.select(contents, "launch", Locale.ENGLISH, random)?.Title
		}.toSet()

		assertEquals(setOf("exact", "wildcard"), selected)
	}

	@Test
	fun returnsNullWhenNoContentSupportsTheScene() {
		assertNull(NotificationContentSelector.select(emptyList(), "launch", Locale.ENGLISH))
		assertNull(NotificationContentSelector.select(listOf(content()), "other", Locale.ENGLISH))
	}

	@Test
	fun resolvesJapaneseAndKoreanWithoutChangingTheSource() {
		val source = content().copy(
			Languages = mapOf(
				"ja" to NotificationLanguage("日本語タイトル", "日本語本文", "開く"),
				"ko" to NotificationLanguage("한국어 제목", "한국어 내용", "열기")
			)
		)
		val original = source.copy()

		val japanese = NotificationContentSelector.select(listOf(source), "launch", Locale.JAPAN)
		val korean = NotificationContentSelector.select(listOf(source), "launch", Locale.KOREA)

		assertEquals(source.copy(Title = "日本語タイトル", Content = "日本語本文", Button = "開く"), japanese)
		assertEquals(source.copy(Title = "한국어 제목", Content = "한국어 내용", Button = "열기"), korean)
		assertNotSame(source, japanese)
		assertNotSame(source, korean)
		assertEquals(original, source)
		assertEquals(source, NotificationContentSelector.select(listOf(source), "launch", Locale.FRENCH))
	}

	@Test
	fun prefersFullTagThenScriptThenRegionThenLanguageWithNormalizedKeys() {
		val locale = Locale.forLanguageTag("zh-Hant-TW")
		var translations = linkedMapOf(
			"ZH" to NotificationLanguage(title = "language"),
			"zH_tW" to NotificationLanguage(title = "region"),
			"zH_hAnT" to NotificationLanguage(title = "script"),
			"zH_hAnT_tW" to NotificationLanguage(title = "full")
		)
		val expected = listOf(
			"zH_hAnT_tW" to "full",
			"zH_hAnT" to "script",
			"zH_tW" to "region",
			"ZH" to "language"
		)

		for ((key, title) in expected) {
			val selected = NotificationContentSelector.select(
				listOf(content().copy(Languages = translations)), "launch", locale
			)
			assertEquals(title, selected?.Title)
			translations = LinkedHashMap(translations - key)
		}
	}

	@Test
	fun prefersRegionOverLanguageAndFallsBackForOtherRegions() {
		val source = content().copy(
			Languages = mapOf(
				"en" to NotificationLanguage(title = "English"),
				"EN_gb" to NotificationLanguage(title = "British English")
			)
		)

		assertEquals(
			"British English",
			NotificationContentSelector.select(listOf(source), "launch", Locale.UK)?.Title
		)
		assertEquals(
			"English",
			NotificationContentSelector.select(listOf(source), "launch", Locale.US)?.Title
		)
	}

	@Test
	fun fallsBackIndependentlyForEachBlankTranslationField() {
		val translations = listOf(
			NotificationLanguage(" \t", "translated body", "translated button"),
			NotificationLanguage("translated title", "\n", "translated button"),
			NotificationLanguage("translated title", "translated body", "")
		)
		val expected = listOf(
			Triple("default title", "translated body", "translated button"),
			Triple("translated title", "default body", "translated button"),
			Triple("translated title", "translated body", "default button")
		)

		translations.zip(expected).forEach { (translation, fields) ->
			val source = content().copy(Languages = mapOf("ja" to translation))
			val selected = NotificationContentSelector.select(listOf(source), "launch", Locale.JAPAN)
			assertEquals(source.copy(Title = fields.first, Content = fields.second, Button = fields.third), selected)
		}
	}

	private fun content() = NotificationContent(
		Title = "default title",
		Content = "default body",
		Button = "default button",
		Scenes = listOf("launch"),
		Route = "app://notification"
	)
}

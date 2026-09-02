package com.mar2sdk.core

import org.junit.Test

import org.junit.Assert.*
import com.mar2sdk.core.log.LogUtil

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
	@Test
	fun addition_isCorrect() {
		assertEquals(4, 2 + 2)
	}

	@Test
	fun formatParams_formatsEntriesInIterationOrder() {
		val params = linkedMapOf<String, Any?>(
			"event" to "ad_loaded",
			"attempt" to 2,
			"enabled" to true
		)

		assertEquals("{event=ad_loaded, attempt=2, enabled=true}", LogUtil.formatParams(params))
	}

	@Test
	fun formatParams_formatsNestedValuesAndNull() {
		val params = linkedMapOf<String, Any?>(
			"metadata" to linkedMapOf("source" to "cache", "tags" to listOf("a", "b")),
			"error" to null
		)

		assertEquals(
			"{metadata={source=cache, tags=[a, b]}, error=null}",
			LogUtil.formatParams(params)
		)
	}
}

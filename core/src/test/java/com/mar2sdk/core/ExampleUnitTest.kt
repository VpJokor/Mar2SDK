package com.mar2sdk.core

import org.junit.Test

import org.junit.Assert.*
import com.mar2sdk.core.log.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExampleUnitTest {
	@Before
	fun setUpMainDispatcher() {
		Dispatchers.setMain(StandardTestDispatcher())
	}

	@After
	fun resetMainDispatcher() {
		Dispatchers.resetMain()
	}

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

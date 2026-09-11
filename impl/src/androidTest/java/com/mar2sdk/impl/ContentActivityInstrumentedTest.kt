package com.mar2sdk.impl

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mar2sdk.core.ad.policy.ScreenAdContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class ContentActivityTestActivity : ContentActivity() {
	override val screenName = "content1"
	val shownContexts: MutableList<ScreenAdContext>
		get() = recordedContexts
	fun invokeResumeForTest() = onResume()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
	}

	override fun showScreenAd(context: ScreenAdContext): Boolean {
		recordedContexts += context
		return false
	}

	companion object {
		val recordedContexts = mutableListOf<ScreenAdContext>()
	}
}

@RunWith(AndroidJUnit4::class)
class ContentActivityInstrumentedTest {
	@Test
	fun firstResumeAndSystemBackUseStartAndLeavePlacements() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		ContentActivityTestActivity.recordedContexts.clear()
		ActivityScenario.launch(ContentActivityTestActivity::class.java).use { scenario ->
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				assertEquals("content1_start", activity.shownContexts.first().areaKey)
				activity.onBackPressedDispatcher.onBackPressed()
				assertEquals("content1_leave", activity.shownContexts.last().areaKey)
				assertTrue(activity.isFinishing || activity.isDestroyed)
			}
			instrumentation.waitForIdleSync()
		}
	}

	@Test
	fun repeatedResumeCallbackDoesNotShowAnotherAd() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		ContentActivityTestActivity.recordedContexts.clear()
		ActivityScenario.launch(ContentActivityTestActivity::class.java).use { scenario ->
			instrumentation.waitForIdleSync()
			scenario.onActivity { it.invokeResumeForTest() }
			instrumentation.waitForIdleSync()
			scenario.onActivity { assertEquals(1, it.shownContexts.size) }
		}
	}

	@Test
	fun recreationDoesNotRepeatStartPlacement() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		ContentActivityTestActivity.recordedContexts.clear()
		ActivityScenario.launch(ContentActivityTestActivity::class.java).use { scenario ->
			instrumentation.waitForIdleSync()
			scenario.recreate()
			instrumentation.waitForIdleSync()
			assertEquals(1, ContentActivityTestActivity.recordedContexts.count { it.areaKey == "content1_start" })
		}
	}
}

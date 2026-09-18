package com.mar2sdk

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mar2sdk.core.ad.AdConfig
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistentNotificationNavigationTest {

	@get:Rule
	val compose = createEmptyComposeRule()

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private var adsWereOpen = true
	private var deliveryId = 0

	@Before
	fun disableAds() {
		instrumentation.runOnMainSync {
			adsWereOpen = AdConfig.isOpen
			AdConfig.isOpen = false
		}
	}

	@After
	fun restoreAds() {
		instrumentation.runOnMainSync { AdConfig.isOpen = adsWereOpen }
	}

	@Test
	fun coldLaunchAndWarmClicksNavigateToTheRequestedScreen() {
		ActivityScenario.launch<MainActivity>(notificationIntent("Action1")).use { scenario ->
			awaitText("内容页面 1")
			var originalActivity: MainActivity? = null
			scenario.onActivity { originalActivity = it }

			deliverNotification(scenario, "Action2")
			awaitText("内容页面 2")
			scenario.onActivity { assertSame(originalActivity, it) }

			deliverNotification(scenario, "persistent")
			awaitText("View/XML 广告示例")
		}
	}

	@Test
	fun recreationKeepsCurrentPageAndTheSameButtonCanBeClickedAgain() {
		ActivityScenario.launch<MainActivity>(notificationIntent("Action1")).use { scenario ->
			awaitText("内容页面 1")
			compose.onNodeWithText("跳转到内容页 2").performClick()
			awaitText("内容页面 2")

			scenario.recreate()
			awaitText("内容页面 2")

			deliverNotification(scenario, "Action1")
			awaitText("内容页面 1")
		}
	}

	@Test
	fun unrelatedAndUnknownIntentsLeaveTheCurrentPageVisible() {
		ActivityScenario.launch<MainActivity>(notificationIntent("Action2")).use { scenario ->
			awaitText("内容页面 2")

			deliverNotification(scenario, "Action1", source = "app")
			compose.onNodeWithText("内容页面 2").assertIsDisplayed()

			deliverNotification(scenario, "unknown")
			compose.onNodeWithText("内容页面 2").assertIsDisplayed()
		}
	}

	@Test
	fun activityButtonsOpenTheCorrespondingViewPages() {
		ActivityScenario.launch<MainActivity>(notificationIntent("persistent")).use {
			awaitText("View/XML 广告示例")
			launchActivityAndReturn("Action3", Content1Activity::class.java, "View/XML 内容页面 1")
			awaitText("View/XML 广告示例")
			launchActivityAndReturn("Action4", Content2Activity::class.java, "View/XML 内容页面 2")
			awaitText("View/XML 广告示例")
		}
	}

	private fun notificationIntent(route: String, source: String = "persistent") =
		Intent(instrumentation.targetContext, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or
				Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
			putExtra("AppOpenFrom", source)
			putExtra("Route", route)
			putExtra("Scene", "persistent")
		}

	private fun deliverNotification(
		scenario: ActivityScenario<MainActivity>,
		route: String,
		source: String = "persistent"
	) {
		val expectedDelivery = ++deliveryId
		instrumentation.targetContext.startActivity(
			notificationIntent(route, source).putExtra(EXTRA_TEST_DELIVERY, expectedDelivery)
		)
		// Wait for onNewIntent to receive this launch before asserting that an ignored route did nothing.
		compose.waitUntil(TIMEOUT_MILLIS) {
			var delivered = false
			scenario.onActivity {
				delivered = it.intent.getIntExtra(EXTRA_TEST_DELIVERY, 0) == expectedDelivery
			}
			delivered
		}
		compose.waitForIdle()
	}

	private fun launchActivityAndReturn(route: String, target: Class<out Activity>, title: String) {
		var openedActivity: Activity? = null
		try {
			instrumentation.targetContext.startActivity(notificationIntent(route))
			// Advance Compose's test clock so the notification's LaunchedEffect can start the Activity.
			compose.waitUntil(TIMEOUT_MILLIS) {
				instrumentation.runOnMainSync {
					openedActivity = ActivityLifecycleMonitorRegistry.getInstance()
						.getActivitiesInStage(Stage.RESUMED)
						.firstOrNull { target.isInstance(it) }
				}
				openedActivity != null
			}
			onView(withText(title)).check(matches(isDisplayed()))
		} finally {
			openedActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
		}
	}

	private fun awaitText(text: String) {
		compose.waitUntil(TIMEOUT_MILLIS) {
			compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
		}
		compose.onNodeWithText(text).assertIsDisplayed()
	}

	private companion object {
		const val TIMEOUT_MILLIS = 10_000L
		const val EXTRA_TEST_DELIVERY = "com.mar2sdk.test.NOTIFICATION_DELIVERY"
	}
}

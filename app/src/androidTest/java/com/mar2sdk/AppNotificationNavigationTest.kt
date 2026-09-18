package com.mar2sdk

import android.app.Activity
import android.app.PendingIntent
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
import com.mar2sdk.core.notify.app.AppNotificationUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNotificationNavigationTest {

	@get:Rule
	val compose = createEmptyComposeRule()

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val pendingIntents = mutableListOf<PendingIntent>()
	private var adsWereOpen = true

	@Before
	fun disableAds() {
		instrumentation.runOnMainSync {
			adsWereOpen = AdConfig.isOpen
			AdConfig.isOpen = false
		}
	}

	@After
	fun restoreAdsAndCancelPendingIntents() {
		pendingIntents.forEach { it.cancel() }
		instrumentation.runOnMainSync { AdConfig.isOpen = adsWereOpen }
	}

	@Test
	fun coldLaunchIsConsumedAndTheSameRouteWorksAfterRecreation() {
		ActivityScenario.launch<MainActivity>(notificationIntent("/recoverPhotos", "cold-photos")).use { scenario ->
			awaitText("内容页面 1")
			compose.onNodeWithText("跳转到内容页 2").performClick()
			awaitText("内容页面 2")

			scenario.recreate()
			awaitText("内容页面 2")

			pendingIntent("/recoverPhotos", "photos-again").send()
			awaitDelivery(scenario, "/recoverPhotos", "photos-again")
			awaitText("内容页面 1")
		}
	}

	@Test
	fun pendingIntentsReuseMainActivityAndKeepTheirOwnRouteAndScene() {
		ActivityScenario.launch<MainActivity>(launcherIntent()).use { scenario ->
			awaitText("View/XML 广告示例")
			var originalActivity: MainActivity? = null
			scenario.onActivity { originalActivity = it }
			// Create all notifications first, including two sharing a route, to catch extras being overwritten.
			val firstPhotos = pendingIntent("/recoverPhotos", "first-photos")
			val videos = pendingIntent("/recoverVideos", "videos")
			val secondPhotos = pendingIntent("/recoverPhotos", "second-photos")

			firstPhotos.send()
			awaitDelivery(scenario, "/recoverPhotos", "first-photos")
			awaitText("内容页面 1")
			scenario.onActivity { assertSame(originalActivity, it) }

			videos.send()
			awaitDelivery(scenario, "/recoverVideos", "videos")
			awaitText("内容页面 2")
			scenario.onActivity { assertSame(originalActivity, it) }

			secondPhotos.send()
			awaitDelivery(scenario, "/recoverPhotos", "second-photos")
			awaitText("内容页面 1")
			scenario.onActivity { assertSame(originalActivity, it) }
		}
	}

	@Test
	fun onlySupportedAppRoutesNavigateAndFilesOpensTheViewPage() {
		ActivityScenario.launch<MainActivity>(notificationIntent("/recoverVideos", "initial-videos")).use { scenario ->
			awaitText("内容页面 2")

			instrumentation.targetContext.startActivity(
				notificationIntent("/recoverFiles", "wrong-source", source = "persistent")
			)
			awaitDelivery(scenario, "/recoverFiles", "wrong-source", source = "persistent")
			compose.onNodeWithText("内容页面 2").assertIsDisplayed()

			pendingIntent("Action1", "wrong-route-source").send()
			awaitDelivery(scenario, "Action1", "wrong-route-source")
			compose.onNodeWithText("内容页面 2").assertIsDisplayed()

			pendingIntent("/unknown", "unknown-route").send()
			awaitDelivery(scenario, "/unknown", "unknown-route")
			compose.onNodeWithText("内容页面 2").assertIsDisplayed()

			var openedActivity: Activity? = null
			try {
				pendingIntent("/recoverFiles", "files").send()
				// Poll with the Compose clock running so LaunchedEffect can dispatch the native navigation.
				compose.waitUntil(TIMEOUT_MILLIS) {
					instrumentation.runOnMainSync {
						openedActivity = ActivityLifecycleMonitorRegistry.getInstance()
							.getActivitiesInStage(Stage.RESUMED)
							.firstOrNull { it is Content1Activity }
					}
					openedActivity != null
				}
				onView(withText("View/XML 内容页面 1")).check(matches(isDisplayed()))
			} finally {
				openedActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
			}
			awaitText("内容页面 2")
		}
	}

	private fun launcherIntent(): Intent {
		val context = instrumentation.targetContext
		return requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		}
	}

	private fun notificationIntent(route: String, scene: String, source: String = "app_push") =
		launcherIntent().apply {
			putExtra("AppOpenFrom", source)
			putExtra("Route", route)
			putExtra("Scene", scene)
		}

	private fun pendingIntent(route: String, scene: String): PendingIntent =
		AppNotificationUtil.getAppPendingIntent(route, scene).also { pendingIntents += it }

	private fun awaitDelivery(
		scenario: ActivityScenario<MainActivity>,
		route: String,
		scene: String,
		source: String = "app_push"
	) {
		compose.waitUntil(TIMEOUT_MILLIS) {
			var delivered = false
			scenario.onActivity {
				delivered = it.intent.getStringExtra("Scene") == scene
			}
			delivered
		}
		compose.waitForIdle()
		scenario.onActivity {
			assertEquals(source, it.intent.getStringExtra("AppOpenFrom"))
			assertEquals(route, it.intent.getStringExtra("Route"))
			assertEquals(scene, it.intent.getStringExtra("Scene"))
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
	}
}

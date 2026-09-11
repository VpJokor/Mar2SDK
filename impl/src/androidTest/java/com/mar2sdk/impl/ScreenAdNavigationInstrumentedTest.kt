package com.mar2sdk.impl

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

internal data class ResumedRoute(val entryId: String, val route: String, val fromRoute: String?)

class ScreenAdNavigationTestActivity : ComponentActivity() {
	internal var navController: NavHostController? = null
		private set
	internal val resumedRoutes = mutableListOf<ResumedRoute>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val controller = rememberNavController()
			SideEffect { navController = controller }
			ObserveScreenAdRoutes(controller)
			NavHost(
				navController = controller,
				startDestination = "main",
				enterTransition = { EnterTransition.None },
				exitTransition = { ExitTransition.None },
				popEnterTransition = { EnterTransition.None },
				popExitTransition = { ExitTransition.None }
			) {
				listOf("main", "content1", "content2").forEach { route ->
					composable(route) { entry ->
						RecordResume(entry, route)
						Text(route)
					}
				}
			}
		}
	}

	@Composable
	private fun RecordResume(entry: NavBackStackEntry, route: String) {
		DisposableEffect(entry) {
			val observer = LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_RESUME) {
					resumedRoutes += ResumedRoute(
						entry.id,
						route,
						entry.savedStateHandle[SCREEN_AD_FROM_ROUTE_KEY]
					)
				}
			}
			entry.lifecycle.addObserver(observer)
			onDispose { entry.lifecycle.removeObserver(observer) }
		}
	}
}

@RunWith(AndroidJUnit4::class)
class ScreenAdNavigationInstrumentedTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()

	@Test
	fun forwardAndBackNavigationSetSourceBeforeResume() = withScenario { scenario ->
		assertNull(awaitResumed(scenario, "main").fromRoute)
		val content1 = navigateTo(scenario, "content1")
		assertEquals("main", content1.fromRoute)
		assertEquals("content1", navigateTo(scenario, "content2").fromRoute)

		scenario.onActivity { it.navController!!.popBackStack() }
		val returned = awaitResumed(scenario, "content1")
		assertEquals(content1.entryId, returned.entryId)
		assertEquals("content2", returned.fromRoute)
	}

	@Test
	fun popUpToAndSingleTopPreserveTheAcceptedSource() = withScenario { scenario ->
		awaitResumed(scenario, "main")
		val content1 = navigateTo(scenario, "content1")
		navigateTo(scenario, "content2")

		scenario.onActivity {
			it.navController!!.navigate("content1") {
				popUpTo("content2") { inclusive = true }
				launchSingleTop = true
			}
		}
		val returned = awaitResumed(scenario, "content1")
		assertEquals(content1.entryId, returned.entryId)
		assertEquals("content2", returned.fromRoute)

		scenario.onActivity {
			it.navController!!.navigate("content1") { launchSingleTop = true }
		}
		assertEquals(returned, awaitResumed(scenario, "content1"))
		scenario.onActivity {
			assertEquals(
				"content2",
				it.navController!!.currentBackStackEntry!!.savedStateHandle
					.get<String>(SCREEN_AD_FROM_ROUTE_KEY)
			)
		}
	}

	@Test
	fun recreationRestoresSourceBeforeResume() = withScenario { scenario ->
		awaitResumed(scenario, "main")
		navigateTo(scenario, "content1")
		navigateTo(scenario, "content2")
		scenario.onActivity { it.navController!!.popBackStack() }
		val beforeRecreation = awaitResumed(scenario, "content1")
		assertEquals("content2", beforeRecreation.fromRoute)

		scenario.recreate()
		assertEquals(beforeRecreation, awaitResumed(scenario, "content1"))
	}

	private fun withScenario(block: (ActivityScenario<ScreenAdNavigationTestActivity>) -> Unit) {
		val intent = Intent(instrumentation.targetContext, ScreenAdNavigationTestActivity::class.java)
		ActivityScenario.launch<ScreenAdNavigationTestActivity>(intent).use(block)
	}

	private fun navigateTo(
		scenario: ActivityScenario<ScreenAdNavigationTestActivity>,
		route: String
	): ResumedRoute {
		scenario.onActivity { it.navController!!.navigate(route) }
		return awaitResumed(scenario, route)
	}

	private fun awaitResumed(
		scenario: ActivityScenario<ScreenAdNavigationTestActivity>,
		route: String
	): ResumedRoute {
		val deadline = SystemClock.uptimeMillis() + 5_000
		var resumed: ResumedRoute? = null
		do {
			instrumentation.waitForIdleSync()
			scenario.onActivity { activity ->
				val entry = activity.navController?.currentBackStackEntry
				if (entry?.destination?.route == route &&
					entry.lifecycle.currentState == Lifecycle.State.RESUMED
				) {
					resumed = activity.resumedRoutes.lastOrNull()?.takeIf { it.entryId == entry.id }
				}
			}
			if (resumed != null) return resumed!!
			SystemClock.sleep(20)
		} while (SystemClock.uptimeMillis() < deadline)
		assertNotNull("Route $route did not reach ON_RESUME", resumed)
		return resumed!!
	}
}

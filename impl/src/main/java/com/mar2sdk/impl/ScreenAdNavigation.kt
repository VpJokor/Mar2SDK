package com.mar2sdk.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController

internal const val SCREEN_AD_FROM_ROUTE_KEY = "base_screen_from_route"

/**
 * 在 NavHost 前调用一次，跟踪页面来源，包括未配置页面广告的页面。
 * 来源路由保存在各导航目的地中，Activity 重建后仍可恢复。
 */
@Composable
fun ObserveScreenAdRoutes(navController: NavController) {
	DisposableEffect(navController) {
		val tracker = ScreenAdRouteTracker()
		val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
			val entry = controller.currentBackStackEntry
			val route = destination.route
			if (entry != null && route != null) {
				tracker.onDestinationChanged(entry.id, route)?.let { fromRoute ->
					entry.savedStateHandle[SCREEN_AD_FROM_ROUTE_KEY] = fromRoute
				}
			}
		}
		navController.addOnDestinationChangedListener(listener)
		onDispose {
			navController.removeOnDestinationChangedListener(listener)
		}
	}
}

internal class ScreenAdRouteTracker {
	private var currentEntryId: String? = null
	private var currentRoute: String? = null

	fun onDestinationChanged(entryId: String, route: String): String? {
		if (entryId == currentEntryId && route == currentRoute) return null
		val fromRoute = currentRoute
		currentEntryId = entryId
		currentRoute = route
		// 首次回调保留随返回栈条目恢复的来源路由，避免覆盖已有值。
		return fromRoute
	}
}

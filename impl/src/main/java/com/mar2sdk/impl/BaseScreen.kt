package com.mar2sdk.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.policy.ScreenAdContext
import com.mar2sdk.core.ad.status.AdFormat

/** 当前导航页面名称。 */
val LocalScreenName = compositionLocalOf { "UnknownScreen" }
val LocalNavBackStackEntry = compositionLocalOf<NavBackStackEntry?> { null }

private val LocalNavigateWithAd = compositionLocalOf<(String, () -> Unit) -> Unit> {
	{ _, navigation -> navigation() }
}

private const val HAS_ENTERED_KEY = "base_screen_has_entered"

/**
 * 返回一个跳转包装器：先展示当前页面的 `${screenName}_to` 广告，广告页结束后再执行跳转。
 * 如果广告无法启动，则立即继续跳转。
 * 调用时传入目标路由：`navigateWithAd(toRoute) { navigation() }`。
 */
@Composable
fun rememberNavigateWithAd(): (toRoute: String, navigation: () -> Unit) -> Unit = LocalNavigateWithAd.current

/** 在广告结束后使用相同的目标路由和指定配置执行导航。 */
@Composable
fun rememberNavigateWithAd(
	navController: NavController
): (toRoute: String, builder: NavOptionsBuilder.() -> Unit) -> Unit {
	val navigateWithAd = rememberNavigateWithAd()
	return remember(navController, navigateWithAd) {
		{ toRoute: String, builder: NavOptionsBuilder.() -> Unit ->
			navigateWithAd(toRoute) {
				navController.navigate(toRoute, builder)
			}
		}
	}
}

/**
 * 注册带页面广告的导航 destination。
 * 在 NavHost 前调用 [ObserveScreenAdRoutes]，以记录进入和返回时的来源路由。
 */
fun NavGraphBuilder.contentComposable(
	route: String,
	content: @Composable () -> Unit
) {
	composable(route) { backStackEntry ->
		val screenName = backStackEntry.destination.route ?: route
		CompositionLocalProvider(
			LocalScreenName provides screenName,
			LocalNavBackStackEntry provides backStackEntry
		) {
			BaseScreen {
				content()
			}
		}
	}
}

/** 页面进入、返回时展示广告，并提供带广告的跳转。 */
@Composable
fun BaseScreen(content: @Composable () -> Unit) {
	val screenName = LocalScreenName.current
	val backStackEntry = LocalNavBackStackEntry.current
	val activity = LocalContext.current.findActivity()
	val lifecycleOwner = backStackEntry ?: activity as? LifecycleOwner
	val adsEnabled = !LocalInspectionMode.current
	val standaloneHasEntered = rememberSaveable(screenName) { mutableStateOf(false) }
	val session = remember(backStackEntry, screenName) { ScreenAdSession(screenName) }
	val launchAd = remember(activity, adsEnabled) {
		{ request: ScreenAdRequest ->
			adsEnabled && activity?.let {
				AdActivity.showAd(
					it,
					ScreenAdContext(
						areaKey = request.areaKey,
						adFormat = AdFormat.INTER,
						adPlatform = AdConfig.defaultPlatform,
						trigger = request.trigger,
						fromRoute = request.fromRoute,
						toRoute = request.toRoute
					)
				)
			} == true
		}
	}
	val navigateWithAd = remember(session, launchAd) {
		{ toRoute: String, navigation: () -> Unit ->
			require(toRoute.isNotBlank()) { "The ad navigation target route must not be blank" }
			session.navigateAfterAd(toRoute, launchAd, navigation)
		}
	}

	if (adsEnabled && lifecycleOwner != null) {
		DisposableEffect(lifecycleOwner, session, launchAd) {
			val observer = LifecycleEventObserver { _, event ->
				when (event) {
					Lifecycle.Event.ON_RESUME -> {
						val hasEntered = backStackEntry?.savedStateHandle
							?.get<Boolean>(HAS_ENTERED_KEY) == true ||
							(backStackEntry == null && standaloneHasEntered.value)
						session.onResume(
							hasEntered = hasEntered,
							markEntered = {
								if (backStackEntry == null) {
									standaloneHasEntered.value = true
								} else {
									backStackEntry.savedStateHandle[HAS_ENTERED_KEY] = true
								}
							},
							launchAd = launchAd,
							fromRoute = backStackEntry?.savedStateHandle
								?.get<String>(SCREEN_AD_FROM_ROUTE_KEY).orEmpty()
						)
					}
					Lifecycle.Event.ON_PAUSE,
					Lifecycle.Event.ON_STOP -> session.onPause()
					else -> Unit
				}
			}

			lifecycleOwner.lifecycle.addObserver(observer)
			if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
				observer.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
			}
			onDispose {
				lifecycleOwner.lifecycle.removeObserver(observer)
			}
		}
	}

	CompositionLocalProvider(LocalNavigateWithAd provides navigateWithAd) {
		Box(modifier = Modifier.fillMaxSize()) {
			content()
		}
	}
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.findActivity()
	else -> null
}

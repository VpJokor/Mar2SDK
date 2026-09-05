package com.mar2sdk.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mar2sdk.core.ad.status.AdFormat

/** 当前导航页面的名称，由导航层注入。 */
val LocalScreenName = compositionLocalOf { "UnknownScreen" }
val LocalNavBackStackEntry = compositionLocalOf<NavBackStackEntry?> { null }

private const val HAS_ENTERED_KEY = "base_screen_has_entered"

/** 为需要广告的内容页统一注册导航 destination。 */
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

/**
 * 自动获取screen名生成areaKey(打开/返回/跳转)
 * 所有页面的公共容器。
 * 页面进入Screen时展示一次广告
 * 从其他页面返回
 */
@Composable
fun BaseScreen(content: @Composable () -> Unit) {
	val screenName = LocalScreenName.current
	val backStackEntry = LocalNavBackStackEntry.current

	if (!LocalInspectionMode.current) {
		val activity = LocalContext.current.findActivity()
		if (backStackEntry != null) {
			DisposableEffect(backStackEntry, activity, screenName) {
				var resumeHandled = false
				var adPendingResume = false
				val observer = LifecycleEventObserver { _, event ->
					when (event) {
						Lifecycle.Event.ON_RESUME -> {
							if (!resumeHandled) {
								resumeHandled = true
								if (adPendingResume) {
									adPendingResume = false
								} else {
									val hasEntered = backStackEntry.savedStateHandle
										.get<Boolean>(HAS_ENTERED_KEY) == true
									val suffix = if (hasEntered) "back" else "start"
									backStackEntry.savedStateHandle[HAS_ENTERED_KEY] = true
									if (activity != null && !activity.isFinishing && !activity.isDestroyed && !AdActivity.showing()) {
										adPendingResume = true
										AdActivity.showAd(activity, AdFormat.INTER, "${screenName}_$suffix")
									}
								}
							}
						}
						Lifecycle.Event.ON_PAUSE,
						Lifecycle.Event.ON_STOP -> resumeHandled = false
						else -> Unit
					}
				}

				backStackEntry.lifecycle.addObserver(observer)
				if (backStackEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
					observer.onStateChanged(backStackEntry, Lifecycle.Event.ON_RESUME)
				}
				onDispose {
					backStackEntry.lifecycle.removeObserver(observer)
				}
			}
		} else {
			LaunchedEffect(activity, screenName) {
				if (activity != null && !activity.isFinishing && !activity.isDestroyed && !AdActivity.showing()) {
					AdActivity.showAd(activity, AdFormat.INTER, "${screenName}_start")
				}
			}
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		content()
	}
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.findActivity()
	else -> null
}

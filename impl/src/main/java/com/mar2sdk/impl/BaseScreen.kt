package com.mar2sdk.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mar2sdk.core.ad.status.AdFormat

/** 当前导航页面的名称，由导航层注入。 */
val LocalScreenName = compositionLocalOf { "UnknownScreen" }

/** 为需要广告的内容页统一注册导航 destination。 */
fun NavGraphBuilder.contentComposable(
	route: String,
	content: @Composable () -> Unit
) {
	composable(route) { backStackEntry ->
		val screenName = backStackEntry.destination.route ?: route
		CompositionLocalProvider(LocalScreenName provides screenName) {
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
	val areaKey = "${screenName}_start"
//	val areaKey = "${screenName}_end"
//	val areaKey = "${screenName}_back"

	if (!LocalInspectionMode.current) {
		val activity = LocalContext.current.findActivity()
		LaunchedEffect(activity, areaKey) {
			if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
				AdActivity.showAd(activity, AdFormat.INTER, areaKey)
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

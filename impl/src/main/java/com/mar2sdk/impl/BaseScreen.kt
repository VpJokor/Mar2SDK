package com.mar2sdk.impl

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.mar2sdk.core.ad.status.AdFormat

/**
 * 所有页面的公共容器。
 * 页面进入组合时展示一次告。
 */
@Composable
fun BaseScreen(areaKey: String = AreaKeys.KEY_TEST, content: @Composable () -> Unit) {

	if (!LocalInspectionMode.current) {
		val activity = LocalContext.current.findActivity()
		LaunchedEffect(activity, areaKey) {
			if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
				AdActivity.showAd(activity, AdFormat.OPEN, areaKey)
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

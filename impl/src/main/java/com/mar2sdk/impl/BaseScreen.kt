package com.mar2sdk.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 所有页面的公共类
 * 1. 页面开启时展示广告
 * 2. 跳转到其他页面时展示完广告再跳转
 */
@Composable
fun BaseScreen(areaKey: String = AreaKeys.KEY_TEST, content: @Composable () -> Unit) {

	// TODO: 公共代码逻辑

	Box(modifier = Modifier.fillMaxSize()) {
		content()
	}

}
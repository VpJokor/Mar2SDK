package com.mar2sdk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mar2sdk.impl.AreaKeys
import com.mar2sdk.impl.BaseScreen

// 内容页面
@Composable
fun ContentScreen() {
	BaseScreen(areaKey = AreaKeys.KEY_TEST) {
		Box (
			modifier = Modifier.fillMaxSize()
		) {
			Text("内容页")
		}
	}
}
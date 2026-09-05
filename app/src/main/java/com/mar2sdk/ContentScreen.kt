package com.mar2sdk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mar2sdk.impl.BaseScreen

@Composable
fun ContentScreen1() {
	BaseScreen {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Text("内容页面 1")
		}
	}
}

@Composable
fun ContentScreen2() {
	BaseScreen {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			Text("内容页面 2")
		}
	}
}

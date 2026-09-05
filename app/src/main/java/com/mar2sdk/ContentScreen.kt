package com.mar2sdk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 内容页1
@Composable
fun ContentScreen1() {
	Box (
		modifier = Modifier.fillMaxSize()
	) {
		Text("内容页1")
	}
}

// 内容页2
@Composable
fun ContentScreen2() {
	Box (
		modifier = Modifier.fillMaxSize()
	) {
		Text("内容页2")
	}
}

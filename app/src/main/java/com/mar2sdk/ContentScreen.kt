package com.mar2sdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ContentScreen1(onNextClick: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize(),
		verticalArrangement = Arrangement.Center
	) {
		Text("内容页面 1")
		Button(onClick = onNextClick) {
			Text("跳转到内容页 2")
		}
	}
}

@Composable
fun ContentScreen2(onPreviousClick: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize(),
		verticalArrangement = Arrangement.Center
	) {
		Text("内容页面 2")
		Button(onClick = onPreviousClick) {
			Text("返回内容页 1")
		}
	}
}

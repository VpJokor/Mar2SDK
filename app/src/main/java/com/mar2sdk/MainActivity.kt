package com.mar2sdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mar2sdk.impl.BaseActivity
import com.mar2sdk.impl.BaseScreen
import com.mar2sdk.impl.DebugActivity

class MainActivity : BaseActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			MainScreen(
				onDebugClick = {
					startActivity(Intent(this@MainActivity, DebugActivity::class.java))
				}
			)
		}
	}
}

@Composable
private fun MainScreen(onDebugClick: () -> Unit) {
	MaterialTheme {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 10.dp)
				.systemBarsPadding()
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(100.dp),
				horizontalArrangement = Arrangement.SpaceBetween
			) {
				Button(
					onClick = onDebugClick,
					modifier = Modifier.weight(1f),
					contentPadding = PaddingValues(10.dp)
				) {
					Text(text = "调试页")
				}
				Spacer(modifier = Modifier.width(10.dp))
				Button(
					onClick = onDebugClick,
					modifier = Modifier.weight(1f),
					contentPadding = PaddingValues(10.dp)
				) {
					Text(text = "开屏页")
				}
			}
		}
	}
}


@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
	MainScreen(onDebugClick = {})
}

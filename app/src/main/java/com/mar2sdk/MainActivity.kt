package com.mar2sdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mar2sdk.impl.DebugActivity
import com.mar2sdk.impl.BaseActivity
import com.mar2sdk.impl.BaseScreen

class MainActivity : BaseActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			MainScreen {
				startActivity(Intent(this@MainActivity, DebugActivity::class.java))
			}
		}
	}
}

@Composable
private fun MainScreen(onDebugClick: () -> Unit) {
	MaterialTheme {
		BaseScreen {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.systemBarsPadding()
			) {
				Button(
					onClick = onDebugClick,
					modifier = Modifier.fillMaxWidth(),
					contentPadding = PaddingValues(10.dp)
				) {
					Text(text = stringResource(R.string.debug_page))
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

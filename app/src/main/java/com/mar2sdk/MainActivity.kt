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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mar2sdk.impl.BaseActivity
import com.mar2sdk.impl.DebugActivity
import com.mar2sdk.impl.LocalScreenName
import com.mar2sdk.impl.SplashScreen

private object Routes {
	const val MAIN = "main"
	const val SPLASH = "splash"
	const val CONTENT_1 = "content1"
	const val CONTENT_2 = "content2"
}

class MainActivity : BaseActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			val navController = rememberNavController()

			MaterialTheme {
				NavHost(
					navController = navController,
					startDestination = Routes.MAIN
				) {
					composable(Routes.MAIN) {
						MainScreen(
							onDebugClick = {
								startActivity(
									Intent(this@MainActivity, DebugActivity::class.java)
								)
							},
							onSplashClick = {
								navController.navigate(Routes.SPLASH)
							},
							onContent1Click = {
								navController.navigate(Routes.CONTENT_1)
							},
							onContent2Click = {
								navController.navigate(Routes.CONTENT_2)
							}
						)
					}
					composable(Routes.SPLASH) {
						SplashScreen()
					}
					contentComposable(Routes.CONTENT_1) {
						ContentScreen1(
							onNextClick = {
								navController.navigate(Routes.CONTENT_2) {
									launchSingleTop = true
								}
							}
						)
					}
					contentComposable(Routes.CONTENT_2) {
						ContentScreen2(
							onPreviousClick = {
								navController.navigate(Routes.CONTENT_1) {
									popUpTo(Routes.CONTENT_2) {
										inclusive = true
									}
									launchSingleTop = true
								}
							}
						)
					}
				}
			}
		}
	}
}

private fun NavGraphBuilder.contentComposable(
	route: String,
	content: @Composable () -> Unit
) {
	composable(route) { backStackEntry ->
		val screenName = backStackEntry.destination.route ?: route
		CompositionLocalProvider(LocalScreenName provides screenName) {
			content()
		}
	}
}

@Composable
private fun MainScreen(
	onDebugClick: () -> Unit,
	onSplashClick: () -> Unit,
	onContent1Click: () -> Unit,
	onContent2Click: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 10.dp)
			.systemBarsPadding()
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Button(
				onClick = onDebugClick,
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(10.dp)
			) {
				Text(text = stringResource(R.string.debug_page))
			}
			Spacer(modifier = Modifier.width(10.dp))
			Button(
				onClick = onSplashClick,
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(10.dp)
			) {
				Text(text = "开屏页面")
			}
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Button(
				onClick = onContent1Click,
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(10.dp)
			) {
				Text(text = "内容页面1")
			}
			Spacer(modifier = Modifier.width(10.dp))
			Button(
				onClick = onContent2Click,
				modifier = Modifier.weight(1f),
				contentPadding = PaddingValues(10.dp)
			) {
				Text(text = "内容页面2")
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
	MaterialTheme {
		MainScreen(
			onDebugClick = {},
			onSplashClick = {},
			onContent1Click = {},
			onContent2Click = {}
		)
	}
}

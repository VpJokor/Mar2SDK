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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.withResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mar2sdk.debug.DebugActivity
import com.mar2sdk.impl.BaseActivity
import com.mar2sdk.impl.ContentActivity
import com.mar2sdk.impl.ObserveScreenAdRoutes
import com.mar2sdk.impl.SplashScreen
import com.mar2sdk.impl.contentComposable
import com.mar2sdk.impl.rememberNavigateWithAd

private object Routes {
	const val MAIN = "main"
	const val SPLASH = "splash"
	const val CONTENT_1 = "content1"
	const val CONTENT_2 = "content2"
}

class MainActivity : BaseActivity() {
	companion object {
		private const val STATE_PENDING_NOTIFICATION_ROUTE = "pending_notification_route"
	}

	// 每次点击都是独立请求，即使连续点击同一个按钮也能再次触发。
	private class NotificationNavigationRequest(val route: String)
	private var pendingNotificationRequest by mutableStateOf<NotificationNavigationRequest?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState == null) {
			handleNotificationIntent(intent)
		} else {
			// 只恢复尚未处理的请求，避免重建时重复执行启动 Intent 中的跳转。
			pendingNotificationRequest = savedInstanceState.getString(STATE_PENDING_NOTIFICATION_ROUTE)
				?.let(::NotificationNavigationRequest)
		}
		enableEdgeToEdge()
		setContent {
			val navController = rememberNavController()
			ObserveScreenAdRoutes(navController)

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
							},
							onViewContentClick = {
								startActivity(
									Intent(this@MainActivity, Content1Activity::class.java)
										.putExtra(ContentActivity.EXTRA_FROM_ROUTE, Routes.MAIN)
								)
							}
						)
					}
					composable(Routes.SPLASH) {
						SplashScreen()
					}
					contentComposable(Routes.CONTENT_1) {
						val navigateWithAd = rememberNavigateWithAd(navController)
						ContentScreen1(
							onNextClick = {
								navigateWithAd(Routes.CONTENT_2) {
									launchSingleTop = true
								}
							}
						)
					}
					contentComposable(Routes.CONTENT_2) {
						val navigateWithAd = rememberNavigateWithAd(navController)
						ContentScreen2(
							onPreviousClick = {
								navigateWithAd(Routes.CONTENT_1) {
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

			val notificationRequest = pendingNotificationRequest
			LaunchedEffect(notificationRequest) {
				if (notificationRequest != null) {
					// NavHost 已完成组合；回到前台后再修改页面或启动 Activity。
					lifecycle.withResumed {
						if (pendingNotificationRequest === notificationRequest) {
							openNotificationDestination(notificationRequest.route, navController)
							pendingNotificationRequest = null
						}
					}
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleNotificationIntent(intent)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString(STATE_PENDING_NOTIFICATION_ROUTE, pendingNotificationRequest?.route)
		super.onSaveInstanceState(outState)
	}

	private fun handleNotificationIntent(intent: Intent) {
		val route = intent.getStringExtra("Route") ?: return
		val supported = when (intent.getStringExtra("AppOpenFrom")) {
			"persistent" -> route in setOf("Action1", "Action2", "Action3", "Action4", "persistent")
			"app_push" -> route in setOf("/recoverPhotos", "/recoverVideos", "/recoverFiles")
			else -> false
		}
		if (supported) {
			pendingNotificationRequest = NotificationNavigationRequest(route)
		}
	}

	/** 宿主在这里配置常驻按钮和普通 APP 通知的目标页面。 */
	private fun openNotificationDestination(route: String, navController: NavHostController) {
		when (route) {
			"Action1", "Action2", "persistent", "/recoverPhotos", "/recoverVideos" -> {
				val destination = when (route) {
					"Action1", "/recoverPhotos" -> Routes.CONTENT_1
					"Action2", "/recoverVideos" -> Routes.CONTENT_2
					else -> Routes.MAIN
				}
				navController.navigate(destination) {
					popUpTo(Routes.MAIN)
					launchSingleTop = true
				}
			}
			"Action3", "Action4", "/recoverFiles" -> {
				val destination = if (route == "Action4") Content2Activity::class.java else Content1Activity::class.java
				startActivity(
					Intent(this, destination)
						.putExtra(ContentActivity.EXTRA_FROM_ROUTE, navController.currentDestination?.route ?: Routes.MAIN)
				)
			}
		}
	}
}

@Composable
private fun MainScreen(
	onDebugClick: () -> Unit,
	onSplashClick: () -> Unit,
	onContent1Click: () -> Unit,
	onContent2Click: () -> Unit,
	onViewContentClick: () -> Unit
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
		Button(
			onClick = onViewContentClick,
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(10.dp)
		) {
			Text(text = "View/XML 广告示例")
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
			onContent2Click = {},
			onViewContentClick = {}
		)
	}
}

package com.mar2sdk

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.messaging.RemoteMessage
import com.mar2sdk.core.firebase.MyFirebaseMessagingService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FcmNotificationTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val context = instrumentation.targetContext
	private val notificationManager = context.getSystemService(NotificationManager::class.java)
	private lateinit var service: MyFirebaseMessagingService

	@Before
	fun createService() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
		}
		instrumentation.runOnMainSync {
			service = MyFirebaseMessagingService()
			ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
				isAccessible = true
			}.invoke(service, context)
			service.onCreate()
		}
	}

	@After
	fun cleanUp() {
		try {
			notificationManager.cancel(NOTIFICATION_ID)
			if (::service.isInitialized) {
				instrumentation.runOnMainSync { service.onDestroy() }
			}
		} finally {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				instrumentation.uiAutomation.dropShellPermissionIdentity()
			}
		}
	}

	@Test
	fun dataMessagePostsWithLoadableSmallIcon() {
		val message = RemoteMessage.Builder("fcm-notification-test")
			.addData("FcmTitle", "FCM 测试标题")
			.addData("FcmContent", "FCM 测试正文")
			.build()

		assertPostedNotification(message, "FCM 测试标题", "FCM 测试正文")
	}

	@Test
	fun emptyMessagePostsDefaultContentWithLoadableSmallIcon() {
		assertPostedNotification(RemoteMessage.Builder("fcm-notification-test").build(), "测试通知", "")
	}

	private fun assertPostedNotification(message: RemoteMessage, title: String, body: String) {
		// Exercise the actual notify() call without starting FCM or the app's follow-up jobs.
		MyFirebaseMessagingService::class.java.getDeclaredMethod("showNotification", RemoteMessage::class.java).apply {
			isAccessible = true
		}.invoke(service, message)

		val deadline = SystemClock.elapsedRealtime() + 5_000
		var notification: Notification? = null
		while (SystemClock.elapsedRealtime() < deadline) {
			notification = notificationManager.activeNotifications.firstOrNull {
				it.id == NOTIFICATION_ID && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
			}?.notification
			if (notification != null) break
			SystemClock.sleep(25)
		}
		assertNotNull("FCM notification was not posted", notification)
		val posted = requireNotNull(notification)
		assertEquals("MyFirebaseMessagingService_CHANNEL", posted.channelId)
		assertEquals(title, posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
		assertEquals(body, posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
		assertNotNull("FCM notification must have a small icon", posted.smallIcon)
		assertNotNull("FCM small icon must resolve in the host app", posted.smallIcon.loadDrawable(context))
	}

	private companion object {
		const val NOTIFICATION_ID = 789012
	}
}

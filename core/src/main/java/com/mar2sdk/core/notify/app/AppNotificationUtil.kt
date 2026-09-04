package com.mar2sdk.core.notify.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.notify.NotificationConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * APP通知管理类
 */
object AppNotificationUtil {

	private const val TAG = "AppNotificationUtil"

	fun init() {
		createChannels()
	}

	private const val CHANNEL_GROUP_ID_PREFIX = "GROUP_ID_"
	private const val CHANNEL_GROUP_NAME_PREFIX = "GROUP_NAME_"
	private const val CHANNEL_ID_PREFIX = "CHANNEL_ID_"
	private const val CHANNEL_NAME_PREFIX = "CHANNEL_NAME_"
	private const val CHANNEL_DES_PREFIX = "CHANNEL_DES_"
	private fun getChannelGroupId(index: Int) = "$CHANNEL_GROUP_ID_PREFIX$index"
	private fun getChannelGroupName(index: Int) = "$CHANNEL_GROUP_NAME_PREFIX$index"
	private fun getChannelId(index: Int) = "$CHANNEL_ID_PREFIX$index"
	private fun getChannelName(index: Int) = "$CHANNEL_NAME_PREFIX$index"
	private fun getChannelDES(index: Int) = "$CHANNEL_DES_PREFIX$index"

	// 创建APP通知的通道
	private fun createChannels() {
		val notificationManager = Core.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		for (index in 1..NotificationConfig.ChannelCount) {
			val groupId = getChannelGroupId(index)
			val groupName = getChannelGroupName(index)
			notificationManager.createNotificationChannelGroup( NotificationChannelGroup(groupId, groupName))
			val channelId = getChannelId(index)
			val channelName = getChannelName(index)
			val channelDes = getChannelDES(index)
			val channel = android.app.NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
				description = channelDes
				setGroup(groupId)
				setShowBadge(true)
				enableVibration(true)
				vibrationPattern = longArrayOf(0, 100, 200, 300)
				lockscreenVisibility = Notification.VISIBILITY_PUBLIC
			}
			notificationManager.createNotificationChannel(channel)
		}
	}

	private val requestCodeGenerator = AtomicInteger(0)
	/** 创建通知点击后的启动 PendingIntent，携带 route 和 scene 参数给宿主导航层。 */
	fun getAppPendingIntent(route: String = "", scene: String = ""): PendingIntent {
		val launchIntent = Core.app.packageManager.getLaunchIntentForPackage(Core.app.packageName)
		launchIntent?.apply {
			putExtra("AppOpenFrom", "app_push")
			putExtra("Route", route)
			putExtra("Scene", scene)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		}
		val requestCode = requestCodeGenerator.incrementAndGet()
		return PendingIntent.getActivity(
			Core.app,
			requestCode,
			launchIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
	}

	// 发送通知
	fun sendNotification(
		context: Context,
		id: Int,
		channelId: String,
		notificationGroupKey: String,
		scene: String = "",
		icon:Int,
		title: String,
		message: String,
		button: String,
		route: String
	) {
		try {
			val pendingIntent = getAppPendingIntent( route, scene)
			val remoteViews = RemoteViews(Core.app.packageName, R.layout.notification_1_mini)
			remoteViews.setImageViewResource(R.id.iv_push,icon)
			remoteViews.setTextViewText(R.id.tv_detail, title)
			remoteViews.setTextViewText(R.id.button, button)
			remoteViews.setOnClickPendingIntent(R.id.root, pendingIntent)
			val bigRemoteViews = RemoteViews(Core.app.packageName, R.layout.notification_1)
			bigRemoteViews.setImageViewResource(R.id.iv_push,icon)
			bigRemoteViews.setTextViewText(R.id.tv_title, title)
			bigRemoteViews.setTextViewText(R.id.tv_message, message)
			bigRemoteViews.setTextViewText(R.id.button, button)
			bigRemoteViews.setOnClickPendingIntent(R.id.root, pendingIntent)
			val notification =
				NotificationCompat.Builder(context, channelId)
					.setContentTitle(title)
					.setSmallIcon(R.drawable.nlogo)
					.setColor(context.getColor(R.color.notification_icon_bg))
					.setCustomContentView(remoteViews)
					.setCustomHeadsUpContentView(remoteViews)
					.setCustomBigContentView(bigRemoteViews)
					.setContentIntent(pendingIntent)
					.setGroup(notificationGroupKey)
					.setAutoCancel(true)
					.setPriority(NotificationCompat.PRIORITY_HIGH)
					.setCategory(NotificationCompat.CATEGORY_MESSAGE)
					.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
					.setOngoing(false)
					.setOnlyAlertOnce(false)
					.setShowWhen(true)
					.setWhen(System.currentTimeMillis())
					.build()
			val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
			manager?.notify(id, notification)
		} catch (e: Exception) {
			Log.e(TAG, "sendNotification: ", e)
		}
	}

}
package com.mar2sdk.core.notify.app

import android.app.Notification
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.AppStatus
import com.mar2sdk.core.Core
import com.mar2sdk.core.R
import com.mar2sdk.core.log.LogAppParam
import com.mar2sdk.core.log.LogNotifyEvent
import com.mar2sdk.core.log.LogNotifyParam
import com.mar2sdk.core.log.LogUtil
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.notify.NotificationContent
import java.util.concurrent.atomic.AtomicInteger

/**
 * APP通知管理类
 */
object AppNotificationUtil {

	private const val TAG = "AppNotificationUtil"

	fun init() {
		createChannels()
	}

	// 发送一批通知
	fun sendNotificationBatch(scene: String) {
		// 通知发送限制
		if ((!NotificationConfig.isForgroundSend) && AppStatus.isForeground) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP在前台不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP在前台不发通知",)
			)
			return
		}
		if ((!NotificationConfig.isScreenOffSend) && (!AppStatus.isScreenOn)) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机熄屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机熄屏不发通知",)
			)
			return
		}
		if ((!NotificationConfig.isScreenLockSend) && AppStatus.isLocked) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机锁屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_batch,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机锁屏不发通知",)
			)
			return
		}

		// TODO: 每隔 6秒发一条，连发3条，这3条等待发送的通知用队列管理

		LogUtil.log(LogNotifyEvent.notify_send_batch, mapOf(LogNotifyParam.isSuccess to true))
		sendNotificationContent(scene)
	}

	// TODO: 清空待发送队列
	fun clearNotifications() {
		if (Core.appMod == AppMod.DEBUG) {
			Toast.makeText(Core.app, "清空待发送队列", Toast.LENGTH_LONG).show()
		}
		LogUtil.log(LogNotifyEvent.clear_notifications, mapOf())
	}

	// 循环使用通知 ID，避免通知数量无限增长。
	val idQueue: ArrayDeque<Int> = ArrayDeque()
	fun getContent(scene: String) : NotificationContent? {
		// TODO: 文案读取策略
		return NotificationConfig.contents.firstOrNull()
	}
	// 发送通知
	fun sendNotificationContent(scene: String) {
		// 通知发送限制
		if ((!NotificationConfig.isForgroundSend) && AppStatus.isForeground) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "APP在前台不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "APP在前台不发通知",)
			)
			return
		}
		if ((!NotificationConfig.isScreenOffSend) && (!AppStatus.isScreenOn)) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机熄屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机熄屏不发通知",)
			)
			return
		}
		if ((!NotificationConfig.isScreenLockSend) && AppStatus.isLocked) {
			if (Core.appMod == AppMod.DEBUG) {
				Toast.makeText(Core.app, "手机锁屏不发通知", Toast.LENGTH_LONG).show()
			}
			LogUtil.log(
				LogNotifyEvent.notify_send_item,
				mapOf(LogNotifyParam.isSuccess to false, LogAppParam.msg to "手机锁屏不发通知",)
			)
			return
		}

		if (idQueue.size >= NotificationConfig.ChannelCount) {
			idQueue.removeFirst()
		}
		val id = (1..NotificationConfig.ChannelCount).firstOrNull { !idQueue.contains(it) } ?: idQueue.removeFirst()
		val randomContent = getContent(scene)
		if (randomContent == null) {
			// TODO: 报错，打点
			Log.e(TAG, "sendNotificationBaths: Content is null")
		}
		val icons = listOf(R.mipmap.ic_push_files, R.mipmap.ic_push_photos, R.mipmap.ic_push_videos, R.mipmap.ic_push_recoverd)
		val content = randomContent!!
		sendNotification(
			context = Core.app,
			id = id,
			channelId = getChannelId(id),
			notificationGroupKey = getChannelGroupName(id),
			scene = scene,
			icon = icons[id -1],
			title = content.Title,
			message = content.Content,
			button = content.Button,
			route = content.Route
		)
		idQueue.add(id)
	}

	// 发送通知
	fun sendNotification(
		context: Context,
		id: Int,
		channelId: String,
		notificationGroupKey: String,
		scene: String,
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
}

package com.mar2sdk.core.notify.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.MainThread
import com.mar2sdk.core.Core
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.notify.NotificationTimer
import com.mar2sdk.core.util.PreferenceUtil
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** 系统持有每条通知的闹钟；进程退出不会丢失，省电模式下允许延迟。 */
internal object NotificationAlarmScheduler {
	internal const val ACTION_FIRE = "com.mar2sdk.core.notify.TIMER"
	private const val KEY_ALARMS = "mar2sdk.notification.timer_alarms"
	private const val KEY_ENABLED = "mar2sdk.notification.timer_enabled"
	private const val ITEM_INTERVAL_SECONDS = 5L

	internal data class Alarm(val scene: String, val index: Int, val hour: Int, val minute: Int, val timeAt: Long)

	@MainThread
	fun start() {
		PreferenceUtil.init()
		PreferenceUtil.commitBoolean(KEY_ENABLED, true)
		refresh()
	}

	@MainThread
	fun refresh() {
		if (!PreferenceUtil.getBoolean(KEY_ENABLED, false)) return
		val old = readAlarms()
		val now = ZonedDateTime.now()
		val updated = buildList {
			for ((scene, timer) in NotificationConfig.timer) {
				if (timer.count <= 0 || timer.HH !in 0..23 || timer.MM !in 0..59) continue
				repeat(timer.count) { index ->
					val timeAt = nextTime(timer, index, now)
					add(Alarm(scene, index, timer.HH, timer.MM, timeAt))
				}
			}
		}
		old.filter { alarm -> updated.none { it.scene == alarm.scene && it.index == alarm.index } }
			.forEach(::cancelAlarm)
		writeAlarms(updated)
		updated.forEach(::schedule)
	}

	@MainThread
	fun stop() {
		PreferenceUtil.commitBoolean(KEY_ENABLED, false)
		readAlarms().forEach(::cancelAlarm)
		writeAlarms(emptyList())
	}

	/** 先持久化下一次时间，使重复广播和进程重建不会再次消费同一条。 */
	@MainThread
	fun consume(intent: Intent): String? {
		if (!PreferenceUtil.getBoolean(KEY_ENABLED, false) || intent.action != ACTION_FIRE) return null
		val scene = intent.getStringExtra("scene") ?: return null
		val index = intent.getIntExtra("index", -1)
		val timeAt = intent.getLongExtra("timeAt", -1L)
		val alarms = readAlarms().toMutableList()
		val position = alarms.indexOfFirst { it.scene == scene && it.index == index && it.timeAt == timeAt }
		if (position < 0 || System.currentTimeMillis() < timeAt) return null
		val alarm = alarms[position]
		val timer = NotificationConfig.timer[scene]
		if (timer == null || index !in 0 until timer.count || timer.HH != alarm.hour || timer.MM != alarm.minute) {
			refresh()
			return null
		}
		val next = alarm.copy(timeAt = nextTime(timer, index, ZonedDateTime.now()))
		alarms[position] = next
		writeAlarms(alarms)
		schedule(next)
		return scene
	}

	internal fun nextTime(timer: NotificationTimer, index: Int, now: ZonedDateTime): Long {
		var candidate = now.toLocalDate().atTime(timer.HH, timer.MM).atZone(now.zone)
			.plusSeconds(index.toLong() * ITEM_INTERVAL_SECONDS)
		if (!candidate.isAfter(now)) {
			candidate = now.toLocalDate().plusDays(1).atTime(timer.HH, timer.MM).atZone(now.zone)
				.plusSeconds(index.toLong() * ITEM_INTERVAL_SECONDS)
		}
		return candidate.toInstant().toEpochMilli()
	}

	private fun schedule(alarm: Alarm) {
		Core.app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
			AlarmManager.RTC_WAKEUP, alarm.timeAt, pendingIntent(alarm)
		)
	}

	private fun cancelAlarm(alarm: Alarm) {
		val pending = pendingIntent(alarm)
		Core.app.getSystemService(AlarmManager::class.java).cancel(pending)
		pending.cancel()
	}

	private fun pendingIntent(alarm: Alarm): PendingIntent = PendingIntent.getBroadcast(
		Core.app, 0, intent(Core.app, alarm), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
	)

	internal fun intent(context: Context, alarm: Alarm) = Intent(context, NotificationAlarmReceiver::class.java).apply {
		action = ACTION_FIRE
		data = Uri.Builder().scheme("mar2sdk-timer").authority(context.packageName)
			.appendPath(alarm.scene).appendPath(alarm.index.toString()).build()
		putExtra("scene", alarm.scene)
		putExtra("index", alarm.index)
		putExtra("timeAt", alarm.timeAt)
	}

	internal fun readAlarms(): List<Alarm> {
		val json = JSONArray(PreferenceUtil.getString(KEY_ALARMS, "[]"))
		return (0 until json.length()).map { index ->
			val item = json.getJSONObject(index)
			Alarm(item.getString("scene"), item.getInt("index"), item.getInt("hour"), item.getInt("minute"), item.getLong("timeAt"))
		}
	}

	private fun writeAlarms(alarms: List<Alarm>) {
		val json = JSONArray()
		alarms.forEach { alarm ->
			json.put(JSONObject().put("scene", alarm.scene).put("index", alarm.index)
				.put("hour", alarm.hour).put("minute", alarm.minute).put("timeAt", alarm.timeAt))
		}
		PreferenceUtil.commitString(KEY_ALARMS, json.toString())
	}
}

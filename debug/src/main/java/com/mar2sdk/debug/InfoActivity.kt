package com.mar2sdk.debug

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.AdConfig
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.impl.admob.AdmobConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.notify.NotificationConfig
import com.mar2sdk.core.notify.NotificationContent
import com.mar2sdk.core.notify.NotificationUtil
import com.mar2sdk.core.common.DBUtil
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class InfoActivity : AppCompatActivity() {
	private var label = ""
	private lateinit var infoAdapter: InfoAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_info)
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}
		findViewById<View>(R.id.back).setOnClickListener {
			finish()
		}

		label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
		val isAdPolicy = label.equals(AD_POLICY_LABEL, ignoreCase = true)
		val isNotificationPolicy = label.equals(NOTIFICATION_POLICY_LABEL, ignoreCase = true)
		findViewById<TextView>(R.id.title).text =
			when {
				isAdPolicy -> getString(R.string.ad_policy)
				isNotificationPolicy -> getString(R.string.notification_policy)
				else -> label
			}
		findViewById<View>(R.id.refresh_btn).apply {
			visibility = if (isAdPolicy || isNotificationPolicy) View.VISIBLE else View.GONE
			contentDescription = getString(
				if (isAdPolicy) R.string.refresh_ad_policy else R.string.refresh_notification_policy,
			)
			tooltipText = contentDescription
			setOnClickListener {
				getData()
				Toast.makeText(
					this@InfoActivity,
					if (isAdPolicy) R.string.ad_policy_refreshed else R.string.notification_policy_refreshed,
					Toast.LENGTH_SHORT,
				).show()
			}
		}
		findViewById<View>(R.id.clear_logs).apply {
			visibility = if (label.equals(LOG_LABEL, ignoreCase = true)) View.VISIBLE else View.GONE
			setOnClickListener { confirmClearLogs() }
		}
		setRecycleView()
		getData()
	}

	private fun setRecycleView() {
		infoAdapter = InfoAdapter(::showInfoDetails)
		findViewById<RecyclerView>(R.id.infos).apply {
			layoutManager = LinearLayoutManager(this@InfoActivity)
			adapter = infoAdapter
			setHasFixedSize(true)
		}
	}

	private fun showInfoDetails(item: InfoItem) {
		val details = buildList {
			add(getString(R.string.info_detail_name, item.title))
			if (item.time.isNotBlank()) {
				add(getString(R.string.info_detail_time, item.time))
			}
			add(
				if (item.useContentLayout) {
					item.content
				} else {
					getString(R.string.info_detail_content, item.content)
				},
			)
		}.joinToString(separator = "\n\n")
		val dialog = AlertDialog.Builder(this)
			.setTitle(R.string.info_details)
			.setMessage(details)
			.setPositiveButton(android.R.string.ok, null)
			.show()
		dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
	}

	private fun getData() {
		clearData()
		when (label.lowercase(Locale.ROOT)) {
			"log" -> loadLogs()
			"user" -> loadUserInfo()
			"config" -> loadConfig()
			CONTENT_LABEL -> loadContent()
			AD_POLICY_LABEL -> loadAdPolicy()
			NOTIFICATION_POLICY_LABEL -> loadNotificationPolicy()
			else -> infoAdapter.submitItems(
				listOf(InfoItem(title = "暂无信息", content = "未知的信息类型：$label")),
			)
		}
	}

	private fun clearData() {
		infoAdapter.submitItems(emptyList())
	}

	private fun loadLogs() {
		lifecycleScope.launch {
			val logs = DBUtil.queryLogs(limit = LOG_DISPLAY_LIMIT + 1)
			val items = logs.take(LOG_DISPLAY_LIMIT).map { log ->
				InfoItem(
					time = LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(log.eventTimeMillis)),
					title = log.eventName,
					content = log.paramsJson,
				)
			}
			val displayedItems = when {
				items.isEmpty() -> listOf(
					InfoItem(title = "暂无日志", content = "本地还没有记录日志"),
				)
				logs.size > LOG_DISPLAY_LIMIT -> items + InfoItem(
					title = "仅显示最近 $LOG_DISPLAY_LIMIT 条",
					content = "更早的日志未展示",
				)
				else -> items
			}
			infoAdapter.submitItems(displayedItems)
		}
	}

	private fun confirmClearLogs() {
		AlertDialog.Builder(this)
			.setTitle(R.string.clear_local_logs)
			.setMessage(R.string.clear_local_logs_confirmation)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.clear) { _, _ -> clearLogs() }
			.show()
	}

	private fun clearLogs() {
		lifecycleScope.launch {
			DBUtil.clearLogs()
			loadLogs()
		}
	}

	private fun loadUserInfo() {
		infoAdapter.submitItems(
			listOf(
				InfoItem(title = "App 模式", content = Core.appMod.name),
				InfoItem(title = "用户类型", content = Core.userType.name),
				InfoItem(
					title = "首次打开时间",
					content = DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(UserInfo.firstOpenTime)),
				),
				InfoItem(
					title = "首次广告收入",
					content = UserInfo.firstAdRevenue.takeIf { it >= 0 }?.toString() ?: "未记录",
				),
				InfoItem(title = "network", content = UserInfo.network),
				InfoItem(title = "campaignId", content = UserInfo.campaignId),
				InfoItem(title = "campaignName", content = UserInfo.campaignName),
				InfoItem(title = "IP 风险", content = UserInfo.riskIP.name),
				InfoItem(title = "包名风险", content = UserInfo.riskPackage.name),
				InfoItem(title = "设备风险", content = UserInfo.riskDevice.name),
				InfoItem(title = "ECPM 类型", content = UserInfo.ecpmType.name),
			),
		)
	}

	private fun loadConfig() {
		val activePlatforms = AdConfig.activePlatforms
			.map { it.name }
			.sorted()
			.joinToString()
			.ifEmpty { "无" }
		val configValues: List<Pair<String, Any>> = listOf(
			"高 ECPM 阈值" to CommonConfig.highEcpm,
			"策略服务地址" to CommonConfig.serverUrl,
			"A/B 测试名称" to CommonConfig.ABTestName,
			"Play Integrity ID" to CommonConfig.PlayIntegrityID,
			"Token 解析路径" to CommonConfig.parseTokenPath,
			"IP 信息路径" to CommonConfig.ipInfoPath,
			"默认广告平台" to AdConfig.defaultPlatform.name,
			"启用广告平台" to activePlatforms,
			"正式admob开屏" to AdmobConfig.openConfig.id,
			"正式admob插屏" to AdmobConfig.interConfig.id,
			"正式admob视频" to AdmobConfig.videoConfig.id,
			"开屏超时(分钟)/池大小" to formatMinutes(AdmobConfig.openConfig.timeout) + " / " + AdmobConfig.openConfig.poolSize,
			"插屏超时(分钟)/池大小" to formatMinutes(AdmobConfig.interConfig.timeout) + " / " + AdmobConfig.interConfig.poolSize,
			"视频超时(分钟)/池大小" to formatMinutes(AdmobConfig.videoConfig.timeout) + " / " + AdmobConfig.videoConfig.poolSize,
			"Thinking Key" to ThinkingConfig.key,
			"Thinking URL" to ThinkingConfig.url,
			"日志截止时间 (小时)" to ThinkingConfig.logEndTime,
			"Singular Key" to SingularConfig.key,
			"Singular Secret" to SingularConfig.secret,
			"Singular 收入上报" to SingularConfig.trackRevenue,
		)

		infoAdapter.submitItems(
			configValues.map { (title, value) ->
				InfoItem(
					title = title,
					content = value.toString(),
					showFullContent = true,
				)
			},
		)
	}

	private fun loadNotificationPolicy() {
		val policyValues = buildList {
			add("发送开关" to buildString {
				appendLine("总开关：${formatSwitch(NotificationConfig.isSend)}")
				appendLine("前台发送：${formatSwitch(NotificationConfig.isForgroundSend)}")
				appendLine("熄屏发送：${formatSwitch(NotificationConfig.isScreenOffSend)}")
				appendLine("锁屏发送：${formatSwitch(NotificationConfig.isScreenLockSend)}")
				appendLine("通知通道数：${NotificationConfig.ChannelCount}")
				append("系统通知权限：${if (NotificationUtil.hasNotiAccess()) "已授权" else "未授权"}")
			})
			add("发送限制" to buildString {
				appendLine("批次全局间隔：${NotificationConfig.intervalSecond} 秒")
				appendLine("1 小时最多批次：${NotificationConfig.max1HBatch} 批")
				appendLine("24 小时最多批次：${NotificationConfig.max24HBatch} 批")
				appendLine("1 小时最多通知：${NotificationConfig.max1HItem} 条")
				append("24 小时最多通知：${NotificationConfig.max24HItem} 条")
			})

			val triggers = NotificationConfig.triggers.toSortedMap()
			if (triggers.isEmpty()) {
				add("触发场景" to "暂无触发场景策略")
			} else {
				triggers.forEach { (scene, trigger) ->
					add("触发场景：$scene" to buildString {
						appendLine("首次延迟：${trigger.firstDelay} 秒")
						appendLine("触发后延迟：${trigger.delay} 秒")
						appendLine("每批通知条数：${trigger.count} 条")
						appendLine("通知样式配置：${formatStyles(trigger.styles)}")
						appendLine("场景批次间隔：${trigger.intervalBatch} 秒")
						append("批内单条间隔：${trigger.intervalItem} 秒")
					})
				}
			}

			val timers = NotificationConfig.timer.toSortedMap()
			if (timers.isEmpty()) {
				add("定时通知" to "暂无定时通知策略")
			} else {
				timers.forEach { (scene, timer) ->
					val time = String.format(Locale.ROOT, "%02d:%02d", timer.HH, timer.MM)
					add("定时通知：$scene" to buildString {
						appendLine("发送时间：$time")
						appendLine("通知条数：${timer.count} 条")
						append("通知样式配置：${formatStyles(timer.styles)}")
					})
				}
			}
		}
		infoAdapter.submitItems(policyValues.map { (title, content) ->
			InfoItem(title = title, content = content, showFullContent = true, useContentLayout = true)
		})
	}

	private fun loadAdPolicy() {
		val policyValues = buildList {
			add("广告开关" to buildString {
				appendLine("总开关：${formatSwitch(AdConfig.isOpen)}")
				appendLine("默认平台：${AdConfig.defaultPlatform.name}")
				append("启用平台：${formatPlatforms(AdConfig.activePlatforms)}")
			})
			add("展示限制" to buildString {
				appendLine("展示超时：${AdConfig.showMaxTime} 毫秒")
				appendLine("展示前最小等待：${AdConfig.showMinTime} 毫秒")
				appendLine("展示模式：${AdConfig.showMod.name}")
				appendLine("1 小时最多展示：${AdConfig.max1H} 次")
				append("24 小时最多展示：${AdConfig.max24H} 次")
			})

			val adUnits = AdConfig.adUnits.toSortedMap()
			if (adUnits.isEmpty()) {
				add("广告位" to "暂无广告位策略")
			} else {
				adUnits.forEach { (areaKey, config) ->
					add("广告位：$areaKey" to buildString {
						appendLine("展示概率：${config.rate}")
						appendLine("每小时最多展示：${config.max1H} 次")
						appendLine("24 小时最多展示：${config.max24H} 次")
						appendLine("展示间隔：${config.interval} 秒")
						appendLine("广告类型：${config.format.name}")
						appendLine("来源路由：${formatRoutes(config.fromRoutes)}")
						append("目标路由：${formatRoutes(config.toRoutes)}")
					})
				}
			}
		}
		infoAdapter.submitItems(policyValues.map { (title, content) ->
			InfoItem(title = title, content = content, showFullContent = true, useContentLayout = true)
		})
	}

	private fun formatSwitch(enabled: Boolean): String = if (enabled) "开启" else "关闭"

	private fun formatPlatforms(platforms: Set<AdPlatform>): String =
		platforms.map { it.name }.sorted().joinToString().ifEmpty { "无" }

	private fun formatRoutes(routes: List<String>): String = routes.joinToString().ifEmpty { "无" }

	private fun formatStyles(styles: List<Int>): String = styles.joinToString().ifEmpty { "无" }

	private fun loadContent() {
		val contents = NotificationConfig.contents
		if (contents.isEmpty()) {
			infoAdapter.submitItems(
				listOf(InfoItem(title = "暂无文案", content = "当前没有可用的通知文案")),
			)
			return
		}

		infoAdapter.submitItems(contents.mapIndexed { index, content ->
			InfoItem(
				title = content.Title.ifBlank { "文案 #${index + 1}" },
				content = formatContent(content),
				showFullContent = true,
				useContentLayout = true,
			)
		})
	}

	private fun formatContent(content: NotificationContent): String = buildString {
		val localizedContents = content.Languages.toSortedMap()
		appendLine("正文：${content.Content}")
		appendLine("按钮：${content.Button}")
		appendLine("场景：${content.Scenes.joinToString().ifBlank { "无" }}")
		appendLine("路由：${content.Route.ifBlank { "无" }}")
		if (localizedContents.isEmpty()) {
			append("多语言：无")
		} else {
			appendLine("多语言：")
			localizedContents.entries.forEachIndexed { index, (language, localized) ->
				appendLine("[$language]")
				appendLine("标题：${localized.title}")
				appendLine("正文：${localized.content}")
				append("按钮：${localized.button}")
				if (index < localizedContents.size - 1) appendLine()
			}
		}
	}

	private fun formatMinutes(milliseconds: Number): String =
		String.format(Locale.ROOT, "%.2f", milliseconds.toDouble() / MILLIS_PER_MINUTE)
			.trimEnd('0')
			.trimEnd('.')

	private data class InfoItem(
		val time: String = "",
		val title: String,
		val content: String,
		val showFullContent: Boolean = false,
		val useContentLayout: Boolean = false,
	)

	private class InfoAdapter(
		private val onItemClick: (InfoItem) -> Unit,
	) : RecyclerView.Adapter<InfoAdapter.InfoViewHolder>() {
		private val items = mutableListOf<InfoItem>()

		private companion object {
			const val VIEW_TYPE_DEFAULT = 0
			const val VIEW_TYPE_CONTENT = 1
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InfoViewHolder {
			val layout = if (viewType == VIEW_TYPE_CONTENT) {
				R.layout.activity_info_content_item
			} else {
				R.layout.activity_info_item
			}
			val itemView = LayoutInflater.from(parent.context)
				.inflate(layout, parent, false)
			return InfoViewHolder(itemView, onItemClick)
		}

		override fun getItemViewType(position: Int): Int =
			if (items[position].useContentLayout) VIEW_TYPE_CONTENT else VIEW_TYPE_DEFAULT

		override fun onBindViewHolder(holder: InfoViewHolder, position: Int) {
			holder.bind(items[position])
		}

		override fun getItemCount(): Int = items.size

		fun submitItems(newItems: List<InfoItem>) {
			val oldSize = items.size
			items.clear()
			if (oldSize > 0) {
				notifyItemRangeRemoved(0, oldSize)
			}
			items.addAll(newItems)
			if (items.isNotEmpty()) {
				notifyItemRangeInserted(0, items.size)
			}
		}

		class InfoViewHolder(
			itemView: View,
			private val onItemClick: (InfoItem) -> Unit,
		) : RecyclerView.ViewHolder(itemView) {
			private val time: TextView = itemView.findViewById(R.id.time)
			private val title: TextView = itemView.findViewById(R.id.title)
			private val content: TextView = itemView.findViewById(R.id.content)

			fun bind(item: InfoItem) {
				itemView.setOnClickListener { onItemClick(item) }
				time.text = item.time
				title.text = item.title
				content.text = item.content
				content.maxLines = if (item.showFullContent) Int.MAX_VALUE else 1
				content.ellipsize = if (item.showFullContent) null else TextUtils.TruncateAt.END
			}
		}
	}

	companion object {
		const val AD_POLICY_LABEL = "ad_policy"
		const val NOTIFICATION_POLICY_LABEL = "notification_policy"
		private const val EXTRA_LABEL = "label"
		private const val LOG_LABEL = "log"
		private const val CONTENT_LABEL = "content"
		private const val LOG_DISPLAY_LIMIT = 100
		private const val MILLIS_PER_MINUTE = 60_000.0
		private val LOG_TIME_FORMATTER = DateTimeFormatter
			.ofPattern("HH:mm:ss")
			.withZone(ZoneId.systemDefault())
		private val DATE_TIME_FORMATTER = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault())
	}
}

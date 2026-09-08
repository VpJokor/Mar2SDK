package com.mar2sdk.debug

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.mar2sdk.core.ad.impl.admob.AdmobConfig
import com.mar2sdk.core.firebase.SingularConfig
import com.mar2sdk.core.log.ThinkingConfig
import com.mar2sdk.core.common.CommonConfig
import com.mar2sdk.core.common.UserInfo
import com.mar2sdk.core.util.DBUtil
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
		findViewById<TextView>(R.id.title).text = label
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
			add(getString(R.string.info_detail_content, item.content))
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
			"正式admob开屏" to AdmobConfig.releaseOpenID,
			"正式admob插屏" to AdmobConfig.releaseInterID,
			"正式admob视频" to AdmobConfig.releaseVideoID,
			"开屏超时(分钟)/池大小" to formatMinutes(AdmobConfig.openTimeout) + " / " + AdmobConfig.openPoolSize,
			"插屏超时(分钟)/池大小" to formatMinutes(AdmobConfig.interTimeout) + " / " + AdmobConfig.interPoolSize,
			"视频超时(分钟)/池大小" to formatMinutes(AdmobConfig.videoTimeout) + " / " + AdmobConfig.videoPoolSize,
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

	private fun formatMinutes(milliseconds: Number): String =
		String.format(Locale.ROOT, "%.2f", milliseconds.toDouble() / MILLIS_PER_MINUTE)
			.trimEnd('0')
			.trimEnd('.')

	private data class InfoItem(
		val time: String = "",
		val title: String,
		val content: String,
		val showFullContent: Boolean = false,
	)

	private class InfoAdapter(
		private val onItemClick: (InfoItem) -> Unit,
	) : RecyclerView.Adapter<InfoAdapter.InfoViewHolder>() {
		private val items = mutableListOf<InfoItem>()

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InfoViewHolder {
			val itemView = LayoutInflater.from(parent.context)
				.inflate(R.layout.activity_info_item, parent, false)
			return InfoViewHolder(itemView, onItemClick)
		}

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
		private const val EXTRA_LABEL = "label"
		private const val LOG_LABEL = "log"
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

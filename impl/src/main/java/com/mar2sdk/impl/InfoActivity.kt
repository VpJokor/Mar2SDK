package com.mar2sdk.impl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
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
import com.mar2sdk.core.policy.PolicyConfig
import com.mar2sdk.core.policy.UserInfo
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
		setRecycleView()
		getData()
	}

	private fun setRecycleView() {
		infoAdapter = InfoAdapter()
		findViewById<RecyclerView>(R.id.infos).apply {
			layoutManager = LinearLayoutManager(this@InfoActivity)
			adapter = infoAdapter
			setHasFixedSize(true)
		}
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
				InfoItem(title = "归因渠道", content = UserInfo.network),
				InfoItem(title = "广告活动 ID", content = UserInfo.campaignId),
				InfoItem(title = "广告活动名称", content = UserInfo.campaignName),
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
		val singularCredentials = if (
			SingularConfig.key.isNotBlank() && SingularConfig.secret.isNotBlank()
		) {
			"已配置"
		} else {
			"未配置"
		}

		infoAdapter.submitItems(
			listOf(
				InfoItem(title = "高 ECPM 阈值", content = PolicyConfig.highEcpm.toString()),
				InfoItem(title = "策略服务地址", content = PolicyConfig.serverUrl),
				InfoItem(title = "A/B 测试名称", content = PolicyConfig.ABTestName),
				InfoItem(title = "Play Integrity ID", content = PolicyConfig.PlayIntegrityID.toString()),
				InfoItem(title = "Token 解析路径", content = PolicyConfig.parseTokenPath),
				InfoItem(title = "IP 信息路径", content = PolicyConfig.ipInfoPath),
				InfoItem(title = "默认广告平台", content = AdConfig.defaultPlatform.name),
				InfoItem(title = "启用广告平台", content = activePlatforms),
				InfoItem(
					title = "AdMob 开屏缓存",
					content = "${durationInMinutes(AdmobConfig.openTimeout)} / 池大小 ${AdmobConfig.openPoolSize}",
				),
				InfoItem(
					title = "AdMob 插屏缓存",
					content = "${durationInMinutes(AdmobConfig.interTimeout)} / 池大小 ${AdmobConfig.interPoolSize}",
				),
				InfoItem(
					title = "AdMob 视频缓存",
					content = "${durationInMinutes(AdmobConfig.videoTimeout)} / 池大小 ${AdmobConfig.videoPoolSize}",
				),
				InfoItem(title = "Thinking 地址", content = ThinkingConfig.url),
				InfoItem(
					title = "Thinking 凭据",
					content = if (ThinkingConfig.key.isBlank()) "未配置" else "已配置",
				),
				InfoItem(title = "日志上报时长", content = "${ThinkingConfig.logEndTime} 小时"),
				InfoItem(title = "Singular 凭据", content = singularCredentials),
				InfoItem(
					title = "Singular 收入上报",
					content = if (SingularConfig.trackRevenue) "开启" else "关闭",
				),
			),
		)
	}

	private fun durationInMinutes(milliseconds: Number): String =
		"${milliseconds.toLong() / MILLIS_PER_MINUTE} 分钟"

	private data class InfoItem(
		val time: String = "",
		val title: String,
		val content: String,
	)

	private class InfoAdapter : RecyclerView.Adapter<InfoAdapter.InfoViewHolder>() {
		private val items = mutableListOf<InfoItem>()

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InfoViewHolder {
			val itemView = LayoutInflater.from(parent.context)
				.inflate(R.layout.activity_info_item, parent, false)
			return InfoViewHolder(itemView)
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

		class InfoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
			private val time: TextView = itemView.findViewById(R.id.time)
			private val title: TextView = itemView.findViewById(R.id.title)
			private val content: TextView = itemView.findViewById(R.id.content)

			fun bind(item: InfoItem) {
				time.text = item.time
				title.text = item.title
				content.text = item.content
			}
		}
	}

	companion object {
		private const val EXTRA_LABEL = "label"
		private const val LOG_DISPLAY_LIMIT = 100
		private const val MILLIS_PER_MINUTE = 60_000L
		private val LOG_TIME_FORMATTER = DateTimeFormatter
			.ofPattern("HH:mm:ss")
			.withZone(ZoneId.systemDefault())
		private val DATE_TIME_FORMATTER = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault())
	}
}

package com.mar2sdk.impl

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mar2sdk.core.AppMod
import com.mar2sdk.core.Core
import com.mar2sdk.core.ad.callback.ShowCallback
import com.mar2sdk.core.ad.status.AdFormat
import com.mar2sdk.core.ad.status.AdPlatform
import com.mar2sdk.core.ad.status.ShowFailResult
import com.mar2sdk.core.log.LogAppParam
import com.mar2sdk.core.log.ThinkingUtil
import com.mar2sdk.core.notify.NotificationUtil
import com.mar2sdk.core.notify.app.AppNotificationUtil
import com.mar2sdk.core.policy.TestMod
import com.mar2sdk.core.policy.UserInfo
import com.mar2sdk.core.policy.UserType

class DebugActivity : AppCompatActivity() {
	private val notificationPermissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			handleNotificationPermissionResult(granted)
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_debug)
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}
		findViewById<View>(R.id.back).setOnClickListener {
			finish()
		}
		findViewById<View>(R.id.refresh_btn).setOnClickListener {
			refreshData()
		}
		findViewById<View>(R.id.user_info_card).setOnClickListener {
			openInfo("User")
		}
		findViewById<View>(R.id.log_btn).setOnClickListener {
			openInfo("Log")
		}
		findViewById<View>(R.id.config_btn).setOnClickListener {
			openInfo("Config")
		}
		findViewById<View>(R.id.send_test_fcm).setOnClickListener {
			Toast.makeText(this@DebugActivity, "发送FCM测试信息", Toast.LENGTH_LONG).show()
		}
		findViewById<View>(R.id.send_notification).setOnClickListener {
			if (NotificationUtil.hasNotiAccess()) {
				AppNotificationUtil.sendNotificationBatch("TEST")
			} else {
				Toast.makeText(Core.app, "没有通知权限", Toast.LENGTH_LONG).show()
			}
		}
		findViewById<View>(R.id.req_notification).setOnClickListener {
			NotificationUtil.reqNotiAccess(
				activity = this@DebugActivity,
				launcher = notificationPermissionLauncher,
				onResult = ::handleNotificationPermissionResult
			)
		}
		findViewById<View>(R.id.per_notification).setOnClickListener {
			if (NotificationUtil.hasNotiAccess()) {
				Core.startFGS()
			} else {
				Toast.makeText(Core.app, "没有通知权限", Toast.LENGTH_LONG).show()
			}
		}

		findViewById<View>(R.id.change_test_mod).setOnClickListener {
			if (Core.testMod == TestMod.FORCE) {
				Core.testMod = TestMod.POLICY
			} else {
				Core.testMod = TestMod.FORCE
			}
			refreshData()
		}
		findViewById<View>(R.id.test_user).setOnClickListener {
			if (Core.appMod == AppMod.TEST && Core.testMod == TestMod.FORCE) {
				val userIndex = UserType.entries.indexOf(Core.userType)
				var nextIndex = userIndex + 1
				if (nextIndex >= UserType.entries.size) nextIndex = 0
				Core.userType = UserType.entries[nextIndex]
				refreshData()
			} else {
				Toast.makeText(this@DebugActivity, "仅 FORCE 测试模式下可用", Toast.LENGTH_LONG).show()
			}
		}

		val adCallback = object : ShowCallback{
			override var areaKey: String
				get() = TODO("Not yet implemented")
				set(value) {}
			override var adFormat: AdFormat
				get() = TODO("Not yet implemented")
				set(value) {}
			override var adPlatform: AdPlatform
				get() = TODO("Not yet implemented")
				set(value) {}

			override fun showFailed(reason: ShowFailResult) {
				TODO("Not yet implemented")
			}

			override fun showSuccess() {
				TODO("Not yet implemented")
			}

			override fun onClicked() {
				TODO("Not yet implemented")
			}

			override fun onAdClosed() {
				TODO("Not yet implemented")
			}

			override fun onPaid() {
				TODO("Not yet implemented")
			}

			override fun onReward() {
				TODO("Not yet implemented")
			}

		}
		findViewById<View>(R.id.test_open).setOnClickListener {
			Core.showOpen(this, adCallback)
		}

	}

	private fun handleNotificationPermissionResult(granted: Boolean) {
		// TODO: 通知权限打点
		ThinkingUtil.setUserAttr(LogAppParam.has_notification_permission, granted)

		if (granted) {
			Toast.makeText(this, "通知权限请求成功", Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(this, "通知权限未授权", Toast.LENGTH_SHORT).show()
		}
	}

	private fun openInfo(label: String) {
		val intent = Intent(this@DebugActivity, InfoActivity::class.java)
		intent.putExtra("label", label)
		startActivity(intent)
	}

	override fun onResume() {
		super.onResume()
		refreshData()
	}

	fun refreshData() {
		findViewById<TextView>(R.id.app_mod).text = Core.appMod.name
		findViewById<TextView>(R.id.user_type).text = Core.userType.name
		findViewById<TextView>(R.id.risk_ip).text = UserInfo.riskIP.name
		findViewById<TextView>(R.id.risk_package).text = UserInfo.riskPackage.name
		findViewById<TextView>(R.id.ecpm_type).text = UserInfo.ecpmType.name
		findViewById<TextView>(R.id.test_mod_name).text = Core.testMod.name
		if (Core.testMod == TestMod.POLICY) {
			findViewById<TextView>(R.id.test_user_name).text = "关闭"
		} else {
			findViewById<TextView>(R.id.test_user_name).text = Core.userType.name
		}

		Toast.makeText(this,"刷新成功", Toast.LENGTH_LONG).show()
	}
}

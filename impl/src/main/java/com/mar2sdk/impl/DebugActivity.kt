package com.mar2sdk.impl

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mar2sdk.core.Core
import com.mar2sdk.core.policy.UserInfo

class DebugActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.activity_debug)
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
			val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
			insets
		}
		findViewById<View>(R.id.refresh_btn).setOnClickListener {
			refreshData()
		}
		findViewById<View>(R.id.user_info_card).setOnClickListener {
			Toast.makeText(this@DebugActivity, "显示用户信息面板", Toast.LENGTH_LONG).show()
		}
		findViewById<View>(R.id.send_test_fcm).setOnClickListener {
			Toast.makeText(this@DebugActivity, "发送FCM测试信息", Toast.LENGTH_LONG).show()
		}
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
		Toast.makeText(this,"刷新成功", Toast.LENGTH_LONG).show()
	}
}
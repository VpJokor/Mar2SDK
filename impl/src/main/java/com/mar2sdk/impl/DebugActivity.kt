package com.mar2sdk.impl

import android.os.Bundle
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
		findViewById<Button>(R.id.refresh_btn).setOnClickListener {
			refreshData()
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
		Toast.makeText(this,"刷新成功", Toast.LENGTH_LONG).show()
	}
}
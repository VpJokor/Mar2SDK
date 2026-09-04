package com.mar2sdk.impl

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class InfoActivity : AppCompatActivity() {
	var label = ""
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
		label = intent.getStringExtra("label") ?: ""
		findViewById< TextView>(R.id.title).text = label
		getData()
		setRecycleView()
	}

	lateinit var recy: RecyclerView
	private fun setRecycleView() {
		recy = findViewById(R.id.infos)
		recy.layoutManager = LinearLayoutManager(this)

	}

	private fun getData() {
		clearData()
		when(label) {
			"log" -> loadLogs()
			"User" -> loadUserInfo()
		}
	}

	private fun clearData() {

	}

	private fun loadLogs() {
		// TODO: 加载日志
	}

	private fun loadUserInfo() {

	}
}
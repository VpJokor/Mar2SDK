package com.mar2sdk

import android.os.Bundle
import android.view.View
import com.mar2sdk.impl.ContentActivity

/** View/XML example showing the shared system-back/leave-ad flow. */
class Content2Activity : ContentActivity() {
	override val screenName = "content2"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_content2)
		findViewById<View>(R.id.content2_back).setOnClickListener {
			onBackPressedDispatcher.onBackPressed()
		}
	}
}

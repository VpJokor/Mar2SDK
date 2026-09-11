package com.mar2sdk

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.mar2sdk.impl.ContentActivity

/** View/XML example showing explicit screenName and leave-ad navigation. */
class Content1Activity : ContentActivity() {
	override val screenName = "content1"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_content1)
		findViewById<View>(R.id.content1_next).setOnClickListener {
			navigateWithAd(
				toScreenName = "content2",
				intent = Intent(this, Content2Activity::class.java)
			)
		}
	}
}

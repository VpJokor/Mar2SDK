package com.mar2sdk.core.notify.app

class AppNotificationManager {

	// 通知触发场景的批次等待队列
	val waitBatchQueue = listOf<String>()

	// 正在发送通知的等待队列
	val sendingQueue = listOf<String>()


	fun loop() {

	}

	fun sendBatch(scene: String) {
		AppNotificationUtil.sendNotificationBatch(scene)
	}

	fun send() {

	}

	// TODO: 清理 waitBatchQueue 和 sendingQueue
	fun clear() {

	}

}
package com.mar2sdk.core.firebase

/** FCM 消息的业务层快照。回调只在进程存活期间有效。 */
data class FcmMessage(
    val messageId: String?,
    val from: String?,
    val sentTime: Long,
    val data: Map<String, String>,
    val title: String?,
    val body: String?,
)

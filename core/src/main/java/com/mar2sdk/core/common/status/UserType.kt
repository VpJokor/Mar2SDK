package com.mar2sdk.core.common.status

/**
 * 用户分类
 * 审核/自然量/普通买量/高价值用户
 */
enum class UserType {
	UNKNOW,
	RISK,
	NATURE,
	COMMON,
	HIGH_VALUE;

	// 广告和通知使用的策略分类，保留原始用户归因。
	internal fun forPolicy(isRisk: Boolean): UserType =
		if (isRisk && (this == UNKNOW || this == NATURE)) RISK else this
}

package com.mar2sdk.core.ad.impl.admob

import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.MobileAds
import java.lang.reflect.Modifier

/** 展示前读取的价格快照，仅供观察；实际收入以 OnPaidEventListener 为准。 */
@ConsistentCopyVisibility
data class AdmobPrice internal constructor(
	/** 单次展示金额，单位为百万分之一美元。 */
	val valueMicros: Long,
	val currencyCode: String,
	/** SDK 内部记录的精度，不代表已确认的展示收入。 */
	val precisionType: Int,
) {
	val revenue: Double get() = valueMicros / 1_000_000.0
	val ecpmMicros: Long get() = valueMicros * 1_000L
	val ecpm: Double get() = valueMicros / 1_000.0
}

/**
 * 移植自 AdvertiseExample 的 AdMobVerifiedPriceExtractors（a3ae8e8f）。
 * 只读取 AdMob 25.3.0 开屏/插屏/激励广告的固定字段链，不调用 SDK 内部方法。
 * 远程 Binder、版本/结构变化或没有有效价格时返回 null。
 * 字段链依赖 SDK 内部实现，读取结果仅供观察，不作为竞价或收入上报依据。
 */
object AdmobPriceProbe {
	private const val SDK_VERSION = "25.3.0"
	private const val ADS = "com.google.android.gms.internal.ads."
	private const val CLIENT = "com.google.android.gms.ads.internal.client."
	private const val DYNAMITE_OPEN_CLIENT = "com.google.android.gms.ads.internal.appopen.client.c"
	private const val DYNAMITE_OPEN_AD = "com.google.android.gms.ads.nonagon.ad.appopen.interstitial.i"
	private const val DYNAMITE_AD_BASE = "com.google.android.gms.ads.nonagon.ad.common.b"
	private const val DYNAMITE_RESPONSE = "com.google.android.gms.ads.nonagon.transaction.a"
	private const val DYNAMITE_MODULE_ID = "com.google.android.gms.ads.dynamite"
	private const val DYNAMITE_MODULE_VERSION = 260480602
	internal const val EVENT_CLASS_NAME = "${CLIENT}zzt"
	internal const val DYNAMITE_EVENT_CLASS_NAME = "${CLIENT}q"

	// AppOpenAd.load 的 zzbgl 回调把本地 zzcvn 包装成 zzbgm。
	// zzcvn 持有 zzcvm，价格响应保存在其父类 zzcya 的 zzb 中。
	internal val openPath = listOf(
		AdmobPriceField("${ADS}zzbgm", "${ADS}zzbgm", "zzb", "${ADS}zzbgq"),
		AdmobPriceField("${ADS}zzcvn", "${ADS}zzcvn", "zza", "${ADS}zzcvm"),
		AdmobPriceField("${ADS}zzcvm", "${ADS}zzcya", "zzb", "${ADS}zzfkn"),
		AdmobPriceField("${ADS}zzfkn", "${ADS}zzfkn", "zzae", EVENT_CLASS_NAME),
	)
	// AdsDynamite 使用独立 ClassLoader；zzbgo 的 Binder 可能仍是同进程对象。
	// 此路径来自设备模块 260480602 的字节码，仅在该模块身份匹配时启用。
	internal val dynamiteOpenPath = listOf(
		openPath.first(),
		AdmobPriceField("${ADS}zzbgo", "${ADS}zzbel", "zza", "android.os.IBinder"),
		AdmobPriceField(DYNAMITE_OPEN_CLIENT, DYNAMITE_OPEN_CLIENT, "a", DYNAMITE_OPEN_AD),
		AdmobPriceField(DYNAMITE_OPEN_AD, DYNAMITE_AD_BASE, "l", DYNAMITE_RESPONSE),
		AdmobPriceField(DYNAMITE_RESPONSE, DYNAMITE_RESPONSE, "ae", DYNAMITE_EVENT_CLASS_NAME),
	)
	internal val dynamiteEventFields = AdmobPriceEventFields("a", "b", "c", "d")
	internal val interstitialPath = listOf(
		AdmobPriceField("${ADS}zzbss", "${ADS}zzbss", "zzc", "${CLIENT}zzbu"),
		AdmobPriceField("${ADS}zzets", "${ADS}zzets", "zzj", "${ADS}zzdmh"),
		AdmobPriceField("${ADS}zzdmh", "${ADS}zzcya", "zzb", "${ADS}zzfkn"),
		AdmobPriceField("${ADS}zzfkn", "${ADS}zzfkn", "zzae", EVENT_CLASS_NAME),
	)
	internal val rewardedPath = listOf(
		AdmobPriceField("${ADS}zzccy", "${ADS}zzccy", "zzb", "${ADS}zzccp"),
		AdmobPriceField("${ADS}zzfke", "${ADS}zzfke", "zzi", "${ADS}zzdvu"),
		AdmobPriceField("${ADS}zzdvu", "${ADS}zzcya", "zzb", "${ADS}zzfkn"),
		AdmobPriceField("${ADS}zzfkn", "${ADS}zzfkn", "zzae", EVENT_CLASS_NAME),
	)
	private val openExtractor = AdmobPriceExtractor(openPath, EVENT_CLASS_NAME)
	private val dynamiteOpenExtractor = AdmobPriceExtractor(
		dynamiteOpenPath, DYNAMITE_EVENT_CLASS_NAME, dynamiteEventFields,
	)
	private val interstitialExtractor = AdmobPriceExtractor(interstitialPath, EVENT_CLASS_NAME)
	private val rewardedExtractor = AdmobPriceExtractor(rewardedPath, EVENT_CLASS_NAME)

	/** 在 onAdLoaded 后、展示前读取。null 表示未获取到有效价格，不表示价格为零。 */
	fun read(ad: Any): AdmobPrice? = try {
		read(ad, MobileAds.getVersion().toString())
	} catch (_: Exception) {
		null
	} catch (_: LinkageError) {
		null
	}

	internal fun read(ad: Any, sdkVersion: String): AdmobPrice? {
		if (sdkVersion != SDK_VERSION) return null
		return when (ad.javaClass.name) {
			openPath.first().runtimeClassName -> openExtractor.read(ad) ?: readDynamiteOpen(ad)
			interstitialPath.first().runtimeClassName -> interstitialExtractor.read(ad)
			rewardedPath.first().runtimeClassName -> rewardedExtractor.read(ad)
			else -> null
		}
	}

	private fun readDynamiteOpen(ad: Any): AdmobPrice? = try {
		val proxy = readAdmobPriceField(ad, dynamiteOpenPath[0])
		val binder = proxy?.let { readAdmobPriceField(it, dynamiteOpenPath[1]) }
		if (binder != null && isSupportedDynamite(binder)) dynamiteOpenExtractor.read(ad) else null
	} catch (_: Exception) {
		null
	} catch (_: LinkageError) {
		null
	}

	private fun isSupportedDynamite(binder: Any): Boolean {
		// 不向 BinderProxy 发起远程调用，也不使用其他模块的字段布局。
		if (binder.javaClass.name != DYNAMITE_OPEN_CLIENT) return false
		val loader = binder.javaClass.classLoader ?: return false
		val descriptor = Class.forName(
			"com.google.android.gms.dynamite.descriptors.$DYNAMITE_MODULE_ID.ModuleDescriptor",
			false,
			loader,
		)
		// 父 ClassLoader 中可能也有本地 SDK 的同名描述类，不能把它当作动态模块版本。
		if (descriptor.classLoader !== loader) return false
		return descriptor.getField("MODULE_ID").get(null) == DYNAMITE_MODULE_ID &&
			descriptor.getField("MODULE_VERSION").getInt(null) == DYNAMITE_MODULE_VERSION
	}
}

internal data class AdmobPriceField(
	val runtimeClassName: String,
	val declaringClassName: String,
	val fieldName: String,
	val fieldTypeName: String,
)

internal data class AdmobPriceEventFields(
	val eventType: String = "zza",
	val precision: String = "zzb",
	val currency: String = "zzc",
	val valueMicros: String = "zzd",
)

internal class AdmobPriceExtractor(
	private val path: List<AdmobPriceField>,
	private val eventClassName: String,
	private val eventFields: AdmobPriceEventFields = AdmobPriceEventFields(),
) {
	fun read(ad: Any): AdmobPrice? = try {
		readPrice(ad)
	} catch (_: Exception) {
		null
	} catch (_: LinkageError) {
		null
	}

	private fun readPrice(ad: Any): AdmobPrice? {
		var current = ad
		for (node in path) {
			current = readAdmobPriceField(current, node) ?: return null
		}
		val eventType = readEventField(current, eventFields.eventType, "int") as? Int ?: return null
		if (eventType != 3) return null
		val precision = readEventField(current, eventFields.precision, "int") as? Int ?: return null
		if (precision != AdValue.PrecisionType.ESTIMATED &&
			precision != AdValue.PrecisionType.PUBLISHER_PROVIDED &&
			precision != AdValue.PrecisionType.PRECISE
		) return null
		val currency = readEventField(current, eventFields.currency, "java.lang.String") as? String ?: return null
		if (currency != "USD") return null
		val valueMicros = readEventField(current, eventFields.valueMicros, "long") as? Long ?: return null
		// 与源实现一致：拒绝零/负数和转换为 eCPM micros 后会溢出的金额。
		if (valueMicros <= 0L || valueMicros > Long.MAX_VALUE / 1_000L) return null
		return AdmobPrice(valueMicros, currency, precision)
	}

	private fun readEventField(target: Any, name: String, type: String): Any? = readAdmobPriceField(
		target,
		AdmobPriceField(eventClassName, eventClassName, name, type),
	)
}

private fun readAdmobPriceField(target: Any, schema: AdmobPriceField): Any? {
	if (target.javaClass.name != schema.runtimeClassName) return null
	var declaringClass: Class<*>? = target.javaClass
	while (declaringClass != null && declaringClass.name != schema.declaringClassName) {
		declaringClass = declaringClass.superclass
	}
	val field = declaringClass?.getDeclaredField(schema.fieldName) ?: return null
	if (field.type.name != schema.fieldTypeName || Modifier.isStatic(field.modifiers)) return null
	field.isAccessible = true
	return field.get(target)
}

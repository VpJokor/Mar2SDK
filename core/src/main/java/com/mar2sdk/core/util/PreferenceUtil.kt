package com.mar2sdk.core.util

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * SDK 内部 SharedPreferences 工具。
 *
 * 所有持久化状态统一写入名为 preference 的私有 SharedPreferences。
 */
object PreferenceUtil {
	private const val PREFERENCES_NAME = "preference"

	@Volatile
	private var sharedPreferences: SharedPreferences? = null

	/** 初始化偏好存储，必须在读写前调用。 */
	@Synchronized
	fun init(context: Context) {
		if (sharedPreferences == null) {
			sharedPreferences = context.applicationContext
				.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
		}
	}

	internal fun isInitialized() = sharedPreferences != null

	@Synchronized
	internal fun resetForTests() {
		sharedPreferences = null
	}

	/** 删除指定 key。 */
	fun removeByKey(key: String?) = update { remove(key) }

	/** 清空 SDK 偏好存储。 */
	fun removeAll() = update { clear() }

	/** 同步写入 String。 */
	fun commitString(key: String?, value: String?) = update { putString(key, value) }

	/** 写入 String，内存立即更新并异步持久化。 */
	fun applyString(key: String, value: String) = update(commit = false) { putString(key, value) }

	/** Synchronously writes a group of String values in one transaction. */
	@Synchronized
	fun commitStrings(values: Map<String, String>) {
		if (values.isEmpty()) return
		update {
			values.forEach { (key, value) ->
				if (key.isNotBlank() && value.isNotBlank()) {
					putString(key, value)
				}
			}
		}
	}

	/** 读取 String。 */
	fun getString(key: String?, failValue: String?): String? =
		sharedPreferences?.getString(key, failValue) ?: failValue

	/** 同步写入 Int。 */
	fun commitInt(key: String?, value: Int) = update { putInt(key, value) }

	/** 读取 Int。 */
	fun getInt(key: String?, failValue: Int): Int =
		sharedPreferences?.getInt(key, failValue) ?: failValue

	/** 同步写入 Long。 */
	fun commitLong(key: String?, value: Long) = update { putLong(key, value) }

	/** 读取 Long。 */
	fun getLong(key: String?, failValue: Long): Long =
		sharedPreferences?.getLong(key, failValue) ?: failValue

	/** 同步写入 Boolean。 */
	fun commitBoolean(key: String?, value: Boolean) = update { putBoolean(key, value) }

	/** 读取 Boolean。 */
	fun getBoolean(key: String?, failValue: Boolean): Boolean =
		sharedPreferences?.getBoolean(key, failValue) ?: failValue

	/** 同步写入 Double；SharedPreferences 无 Double 类型，内部以字符串保存。 */
	fun commitDouble(key: String?, value: Double) = commitString(key, value.toString())

	/** 读取 Double。 */
	fun getDouble(key: String?, failValue: Double): Double {
		val storedValue = sharedPreferences?.getString(key, "") ?: return failValue
		return storedValue.takeIf(String::isNotEmpty)?.toDouble() ?: failValue
	}

	/** 同步写入 Float。 */
	fun commitFloat(key: String?, value: Float) = update { putFloat(key, value) }

	/** 读取 Float。 */
	fun getFloat(key: String?, failValue: Float): Float =
		sharedPreferences?.getFloat(key, failValue) ?: failValue

	private inline fun update(
		commit: Boolean = true,
		block: SharedPreferences.Editor.() -> Unit,
	) {
		val preferences = sharedPreferences ?: return
		val pendingChanges = preferences.edit().apply(block)
		if (commit) pendingChanges.commit() else pendingChanges.apply()
	}
}

/**
 * SharedPreferences 属性委托。
 *
 * 支持 Int、Long、Boolean、Float、String、Double 这些 SDK 内部常用类型。
 */
class PreferenceDelegate<T>(
	private val key: String,
	private val defaultValue: T
) : ReadWriteProperty<Any?, T> {

	/** 根据默认值类型从 PreferenceUtil 读取对应类型。 */
	override fun getValue(thisRef: Any?, property: KProperty<*>): T {
		return when (defaultValue) {
			is Int -> PreferenceUtil.getInt(key, defaultValue) as T
			is Long -> PreferenceUtil.getLong(key, defaultValue) as T
			is Boolean -> PreferenceUtil.getBoolean(key, defaultValue) as T
			is Float -> PreferenceUtil.getFloat(key, defaultValue) as T
			is String -> PreferenceUtil.getString(key, defaultValue) as T
			is Double -> PreferenceUtil.getDouble(key, defaultValue) as T
			else -> throw IllegalArgumentException("Unsupported type.")
		}
	}

	/** 根据写入值类型保存到 PreferenceUtil。 */
	override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
		when (value) {
			is Int -> PreferenceUtil.commitInt(key, value)
			is Long -> PreferenceUtil.commitLong(key, value)
			is Boolean -> PreferenceUtil.commitBoolean(key, value)
			is Float -> PreferenceUtil.commitFloat(key, value)
			is String -> PreferenceUtil.commitString(key, value)
			is Double -> PreferenceUtil.commitDouble(key, value)
			else -> throw IllegalArgumentException("Unsupported type.")
		}
	}
}

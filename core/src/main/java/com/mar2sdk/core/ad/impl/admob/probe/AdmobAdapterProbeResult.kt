package com.mar2sdk.core.ad.impl.admob.probe

/**
 * 解析一次已加载 AdMob 响应后得到的语义结果。
 *
 * 结果必须把“已经证明的代理价格上界”和所有不确定情况区分开。AdMob 成功
 * 加载但没有确认探针，和 AdMob 根本没有可展示广告，是两个不同的状态；两者
 * 都不能用 `upperEcpmMicros = 0` 表示。
 *
 * 最终实现应提供类似以下状态/原因：
 *
 * - `KNOWN_UPPER_BOUND`：至少一个探针在唯一胜出来源之前被确认执行，代理上界
 *   取已执行探针 eCPM 的最小值；
 * - `NO_CONFIRMED_PROBE`：AdMob 加载成功，但没有证据证明探针执行过；
 * - `PROFILE_UNVERIFIED` / `MISSING_WINNER`：无法确认预期的聚合配置或唯一胜出项；
 * - `CONFIG_MISMATCH`：实例、标签、Parameter、币种或价格与冻结的 [ProbeConfig]
 *   不一致；
 * - `ORDER_UNVERIFIED` / `PROBE_LOADED_UNEXPECTEDLY`：中介顺序无法验证，或探针
 *   出现在胜出来源之后，使上界假设失效；
 * - `UNEXPECTED_PROBE_ERROR`：疑似探针的条目因适配器主动 no-fill 以外的原因失败；
 * - `CURRENCY_MISMATCH`：响应不能按当前配置的币种比较。
 *
 * `upperEcpmMicros` 只有在 `KNOWN_UPPER_BOUND` 时才有效，单位是 USD eCPM 微单位，
 * 不是单次展示金额，也不是真实 paid revenue。调用方可以用它和 CloudX 报价比较，
 * 但本结果本身不负责选择或展示广告。选中的广告、请求/响应 ID、已执行探针 ID
 * 以及配置版本应一起保存，便于诊断并避免并发缓存串值。
 */
class AdmobAdapterProbeResult {

}

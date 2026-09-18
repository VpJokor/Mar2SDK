package com.mar2sdk.core.ad.impl.admob.probe

/**
 * 作为虚拟价格底探针使用的 AdMob 自定义事件适配器。
 *
 * AdMob 后台中的每个探针实例都会配置一个手动 eCPM 和一个标签，例如
 * `Probe_F15.5`。当 AdMob 走到这一行时，适配器必须立即返回可识别的主动
 * no-fill；[AdmobAdapterProxyReader] 再据此判断这个价格层级是否在真实胜出来源之前
 * 被尝试过。
 *
 * 这不是一个真正的广告网络适配器，必须遵守以下约束：
 *
 * - 不发起网络请求，也不等待定时器；
 * - 不调用成功回调、不返回广告、不展示界面、不触发奖励；
 * - 使用稳定的错误域和错误码（例如 `com.mar2sdk.admob.probe` / `10001`），
 *   让读取器可以把主动 no-fill 与类缺失、初始化失败、超时或其他网络错误区分开；
 * - 一次请求最多发送一次终态回调。
 *
 * Google Mobile Ads SDK 会通过反射实例化此类，因此真正实现时必须保留公开的
 * 无参构造器，并在 consumer ProGuard 规则中保留该类。扩展到其他广告格式时，
 * 必须显式实现对应的加载入口；未实现的格式不能被默认失败误认为探针已执行。
 */
class AdmobProxyAdapter {

}

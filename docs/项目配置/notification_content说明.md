# `notification_content.json` 通知文案说明

文件位于 `core/src/main/res/raw/notification_content.json`，只保存通知标题、正文和按钮文案；发送策略见 [`notification_config说明.md`](notification_config说明.md)。SDK 初始化时读取打包资源，随后使用本地 `Preference` 中保存的 `contents` 整体覆盖资源内容。

## 顶层字段

| 字段 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `contents` | 对象数组 | `[]` | 通知文案列表。每个元素代表一组文案。显式设置为空数组会清空已保存文案。 |

## 文案对象字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `Title` | 字符串 | 默认通知标题。 |
| `Content` | 字符串 | 默认通知正文。 |
| `Button` | 字符串 | 通知卡片按钮文字。 |
| `Scenes` | 字符串数组 | 文案适用的场景标签，应与策略中的 `triggers` 或 `timer` 键一致（区分大小写）。 |
| `Route` | 字符串 | 用户点击通知后传给宿主应用的路由字符串。 |
| `Languages` | 对象 | 按语言代码提供本地化文案。值对象使用小写 `title`、`content`、`button` 字段，缺失字段按空字符串处理。 |

当前发送器固定读取 `contents` 第一项，尚未依据 `Scenes` 筛选或自动依据系统语言切换 `Languages`；这些字段会被解析并保存，供后续业务使用。`styles` 属于通知策略文件，不属于文案对象。

## 示例

```json
{
  "contents": [
    {
      "Title": "Lost Photos Found!",
      "Content": "We found 23 deleted photos. Tap to restore them instantly.",
      "Button": "Check",
      "Scenes": ["screen_on_a", "timer-a"],
      "Route": "/recoverPhotos",
      "Languages": {
        "ja": {
          "title": "紛失した写真が見つかりました!",
          "content": "削除された写真が見つかりました。タップして復元できます。",
          "button": "確認"
        }
      }
    }
  ]
}
```

修改 `NotificationConfig.contents` 后调用 `saveNotificationConfig()` 保存；重新启动应用会读取已保存的内容。示例资源：[`notification_content.json`](../../core/src/main/res/raw/notification_content.json)。

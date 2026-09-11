# View/XML 页面广告使用

传统页面继承 `com.mar2sdk.impl.ContentActivity`，直接声明广告配置中的业务页面名：

```kotlin
class Content1Activity : ContentActivity() {
    override val screenName = "content1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content1)
        findViewById<View>(R.id.next).setOnClickListener {
            navigateWithAd(
                toScreenName = "content2",
                intent = Intent(this, Content2Activity::class.java)
            )
        }
    }
}
```

页面首次恢复、从子页面返回和跳转前会分别请求 `${screenName}_start`、`${screenName}_back` 和 `${screenName}_leave`。系统返回键也会先请求 leave 广告。业务代码必须通过 `navigateWithAd` 跳转；直接调用 `startActivity` 会绕过跳转前广告和来源路由记录。

`navigateWithAd` 会把当前页面名写入目标 Intent，目标页面应继续使用自己的 `screenName`，不要从类名或混淆名称推导页面名。现有 Compose 页面继续使用原有 `NavController` 广告逻辑。

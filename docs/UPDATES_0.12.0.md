# 0.12.0 身份恢复与个人仓库更新

用户批准移植连续空响应恢复，并要求通过 GitHub latest Release 自动更新。两个改动保持独立：播放恢复沿用同一身份事务，更新器只在前台浏览时运行。

## 身份恢复

0.11.0 已有 recoverEmpty=true，一轮所有模型端点均空就会验证候选身份；本次收紧为连续两轮后才验证候选。同一 PlayerDeviceStore 跨仓库实例共享计数、恢复锁及五分钟单调时钟冷却。同一持续失败期只试一次，注册或验证失败保留旧持久化身份；使用该身份得到有效播放结果后复位。网络/解析/不支持编码错误打断空响应计数，不作为空响应；已取消的请求不发起注册。明确拒绝仍走同一有界事务。

没有复制另一个仓库的内存身份实现，也没有添加绕过冷却的第二条 rotate 入口。账号登录数据、预加载默认关闭及当前单播放器连续队列保持原有行为。候选仍须通过原集数的取流验证后才保存。

## 自动更新

固定查询 `https://api.github.com/repos/WayneShao/hongguoTV/releases/latest`，不需要 Token。只接受正式 release；固定个人仓库、标签 `personal-X.Y.Z`、精确资产名。TV / X3Pro-Keyboard / X3Pro-Full 独立选择；Keyboard 不会自动变成 Full。版本比较使用各 APK 的 versionCode，不比较文件名字符串。

每个 release 正文尾部必须包含一份隐藏的结构化元数据，避免依赖人工编写的中文更新日志：

```html
<!-- hongguotv-update {"schema":1,"variants":{"TV":{"packageName":"com.hongguotv.nativeapp","versionName":"0.12.0-local","versionCode":21,"minSdk":26},"X3Pro-Keyboard":{"packageName":"com.hongguotv.rayneo","versionName":"0.12.0-rayneo-keyboard","versionCode":27,"minSdk":26},"X3Pro-Full":{"packageName":"com.hongguotv.rayneo","versionName":"0.12.0-rayneo-full","versionCode":27,"minSdk":26}}} -->
```

SHA-256 和字节数直接取自 GitHub 资产字段。资产文件名须精确为 `hongguoTV-<variant>-X.Y.Z.apk`；缺失、重复、草稿、预发布、无摘要或元数据不一致均不下载。唯一历史兼容为 personal-0.11.0 的明确版本说明行。后续版本必须生成结构化元数据。

启动前台浏览后约 8 秒检查，检查间隔 6 小时；设置页可手动检查，自动检查并下载可关闭，稍后提示延期一天。进入播放或后台取消请求；只有前台空闲时提示安装。雷鸟使用原双目对话框，TV 使用原生对话框。下载大小上限 220MiB，覆盖带离线语音的 Full；下载无五分钟总时限，仍保留连接/读取超时和取消。下载完成后验证长度、SHA-256、包名、versionName/versionCode、minSdk 和原签名，确认后交给 Android 安装器。普通未 root 设备仍需系统安装确认。

发布顺序：构建三个签名包；从实际 APK 核对版本、包名和 minSdk；先建草稿并附元数据；上传三个 APK 和 SHA256SUMS；核对 GitHub 资产摘要与本地一致；最后发布并设为 latest。避免客户端发现未上传完的 release。

0.11.0 没有启用此个人仓库更新器，首次需要覆盖安装 0.12.0；以后版本沿用发布约定即可应用内升级。

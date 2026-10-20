# 0.2.0 云端验证结果（含「骑行」系统磁贴）

- Kotlin/Robolectric：共 49 项通过，0 失败、0 跳过。磁贴/服务/启动入口 19 项、GPS/状态机/ODO 19 项、持久化/恢复/对账 11 项。平台模拟为 API 35，不是 OriginOS 真机。
- `node --test tests/model.test.js android/tests/projection.test.cjs`：11 项通过，0 失败、0 跳过。原模型文件保留全部既有断言。
- 桌面 Chromium：初始化、手动里程、部分/满油、金额补录、GPS 原动画更新、未知距离停显、旧 JSON 备份、localStorage 持久化和油价离线降级通过。动画验证改为等待实际期望数值，避免固定延时在繁忙构建机上误报。
- `assembleDebug` / `lintDebug` 成功；lint 0 错误、6 警告。仅对 API 26–33 必需的旧版 Tile Activity 启动重载作局部说明性 lint 抑制；API 34+ 始终使用 PendingIntent，没有关闭整类检查或建立 baseline。
- 真实 APK 校验：包名保持 `io.github.bieshuowola.fuelmanager`，versionCode=2、versionName=0.2.0；manifest 中存在受 BIND_QUICK_SETTINGS_TILE 保护的 TileService、QS 动作和长按入口；资源标签精确为「骑行」；DEX 中有磁贴、原生启动页和定位服务代码，当前 Android 专用网页资源与 APK 一致。
- `apksigner verify` / `zipalign -c -P 16 4` 通过，同一公开测试签名，支持覆盖旧 0.1.0。根目录网站、manifest、图标、README 和原测试与基准 b5d38d1 保持原样。
- 本地已校验 APK：`fuel-manager-0.2.0-debug.apk`，47,173,557 字节；本地 SHA256 `ba87ca4297afd0d81ad70b9d4e0d5b5540c29dc57535941045b50c8c1b1ae930`。远端构建 APK 可能字节不同，须核对其对应 SHA256 文件。

磁贴测试覆盖：默认关闭、严格标签/图标/系统注册、实际定位订阅后高亮、点按停止保存一次、快速点击取消/延迟命令、重复启动单订阅、暂停、过期通知继续、定位关闭、权限拒绝/撤销、系统拒绝 location FGS、独立 Activity 可见后请求服务、不加载 WebView、旧服务销毁不取消新点击、异常进程/重启保存及回到关闭、红灯保持/长停车结束。新增缓慢拥堵回归避免低速位移被误判为停车。

未执行：vivo X100 / Android 16 的控制中心实际添加、图标/高亮、OEM 长按规则、真实锁屏/熄屏/后台保活、真实 GNSS 距离/电耗及 OCR 准确率。测试中 SystemUI 和系统服务拒绝使用平台模拟，不能当作 Android 16 实机通过。

当前工具没有上传或文件附件交付 API；共享文件不是用户附件。通过成功构建后的 GitHub 测试 Release 提供经过下载核验的独立 ASCII 文件名 APK 直链及 SHA256，Actions ZIP 只作为备用。详见 RIDE_TILE.md 和 DEVICE_ACCEPTANCE.md。

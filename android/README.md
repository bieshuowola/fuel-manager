# Fuel Manager Android 0.1.0

独立 Android 工程，不改动仓库根目录网站。包名 `io.github.bieshuowola.fuelmanager`，minSdk 26，target/compileSdk 36。使用 WebViewAssetLoader 打包原页面离线资源，原油耗模型、CSS、备份版本和键 `nk250_fuel_manager_v1` 保留；生成的专用适配层只在 APK 内生效。

点击原页面左上油泵图标打开简洁原生操作菜单。通过原界面导入网页 JSON（Chrome 与 WebView 不共享存储），或在实际加满时初始化。先在室外静止建立定位，再骑行。原加油/里程/历史/设置入口照常使用，不会插入 GPS mileage 事件到真实账本。

功能：确认本车后的定位前台服务、系统融合/GNSS、单调时钟去重、精度/跳跃/缺口过滤、短停状态机、原子持久化、进程/重启中断标记、两层里程账本、真实 ODO 对账、全 ODO/末三位/相机框选本机 OCR、旧格式/合并备份及 10 份本机恢复点。没有精确轨迹存储或上传，OCR 照片仅缓存并在读取后删除。系统网络安全配置仅允许油价域名通过系统证书验证，其他外部 HTTPS 连接包括依赖诊断端点将因不受信任而失败；不会关闭 TLS 校验。

自动化边界：Google Play services 可用且用户授权时，低功耗车辆活动转换发出确认通知；不会凭 IN_VEHICLE 自动认定摩托车，不申请后台定位权限，不从后台强启定位服务。无 GMS 的 vivo 使用“记录本次摩托车骑行”。已合法启动的记录与界面解耦；强制停止、OriginOS 清理、重启和省电仍可能中断。未启动/未响应提示的骑行仍需 ODO 补录。本版本不承诺完全无感自动识别每趟行程。

已知的未知距离、待确认行程、初始定位未建立时，主页面暂停显示油量/续航数值并复用现有提醒。短暂恢复时重新锚定，绝不拼接缺口。待确认行程只能整体确认/排除，混合交通方式请使用 ODO 校准。GPS 临时输入包含单次 5%（至少 50m）余量，并非已验证的误差上限。首次 5 个校准区间每趟结束或 100km 提醒，之后 150km；不基于单次偏差训练未来比例因子。真实 ODO 提交后，当前区间的 GPS 预测完全消除，用原模型重放，不把 4km 之类的差额继续加到未来。

## 构建与验证

需要完整 JDK 17 或 21、Python 3、Node 24、Android SDK 36 / Build Tools 36.0.0。已带 Gradle 8.13 Wrapper 及发行包 SHA-256，依赖版本固定。SDK 许可证由开发者正常接受。

```sh
# 仓库根目录
node --test tests/model.test.js android/tests/projection.test.cjs
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

产物 `android/app/build/outputs/apk/debug/app-debug.apk`。本地 SDK 路径通过未跟踪的 `android/local.properties` 或 `ANDROID_HOME` 配置。受限云环境请把 `GRADLE_USER_HOME` / `ANDROID_USER_HOME` 放在可写目录，沿用平台 HTTPS 代理和系统 CA；不要关闭 TLS 验证。Robolectric 测试使用可写 Gradle 缓存中的专用测试 home。

桌面 Chromium 回归（原 UI 流程与动画目标，不等同实机）：

```sh
# 安装到临时目录，避免改变网站依赖
npm install --prefix /tmp/fuel-browser playwright@1.56.1
/tmp/fuel-browser/node_modules/.bin/playwright install chromium
python3 android/scripts/prepare_web.py
fuel_chromium_path=$(node -e "process.stdout.write(require('/tmp/fuel-browser/node_modules/playwright').chromium.executablePath())")
PLAYWRIGHT_MODULE=/tmp/fuel-browser/node_modules/playwright \
CHROMIUM_PATH="$fuel_chromium_path" node android/tests/browser.cjs
```

GitHub Actions 的 `Android Debug APK` 在 `android/**` 分支推送、PR 或手动触发时构建，Artifact `fuel-manager-debug` 包含 APK、SHA256、单元测试与 lint 报告，保留 30 天。没有被推送或实际执行的 workflow 不能当作构建成功证据。

## 测试签名与升级

`android/debug/fuel-manager-debug.p12` 是专用公开测试密钥，口令 `android`，仅签 Debug，便于不同机器和 Actions 生成可覆盖安装的初版。证书 SHA256：`57ccef10981c04fd8e1179be5e551d0f013a59874188cdf610dce03a5d9c5b9f`。这不是个人签名，也不应用于生产发布；任何人可用此公开调试密钥签包，因此只安装自己核验来源和 SHA256 的测试构建。后续覆盖安装保持包名/签名一致并递增 versionCode；更换正式签名须另行规划迁移。卸载/清除数据前必须导出备份。

详见 [架构](ARCHITECTURE.md) 和 [vivo X100 / Android 16 验收步骤](DEVICE_ACCEPTANCE.md)。

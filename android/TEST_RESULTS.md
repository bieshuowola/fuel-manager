# 本次云端验证结果

- `node --test tests/model.test.js android/tests/projection.test.cjs`：11 项通过，0 失败、0 跳过；其中原模型为 1 个测试文件，包含多条模型断言。
- `testDebugUnitTest`：GPS / 状态机 / ODO 18 项，AtomicFile / 持久化 / 对账 / 恢复 11 项，共 29 项通过，0 失败、0 跳过。定位数据是固定手工场景回放，不是真实骑行轨迹。
- 桌面 Chromium：原界面初始化、手动里程、部分/满油、金额补录、GPS 原动画更新、未知距离停显、旧 JSON 备份、localStorage 重启与在线油价失败降级通过。不是 Android WebView 实机验收。
- `assembleDebug` / `lintDebug`：成功；lint 0 错误、6 警告（旧版固定依赖提示、必要的 JavaScript、中文文案及可选 KTX 建议）。没有禁用规则或创建 lint baseline。
- `apksigner verify --print-certs`、`zipalign -c -P 16 -v 4` 通过。ML Kit arm64-v8a 和 x86_64 ELF LOAD 16KB 对齐已检查。
- APK 已打包原 SVG / manifest 字节以及当前 Android 专用页面；根目录网站、README 和原测试文件与基准提交 `b5d38d1ac2a850afdebb674b02f69bcae945e4c4` 完全一致。
- Debug APK：47,492,695 字节；SHA256 `5a6e944ad7c9b8f7f58d9eb3dadb94fced3fdae102950b7b78bc977168067486`。专用公开测试签名，证书指纹见 README。未安装到真实设备。

未执行：vivo X100 / Android 16 的实际安装、后台/锁屏/重启、省电、真实 GPS 误差/漏记率/耗电、相机 OCR 效果及覆盖升级。没有连接 Android 实机或模拟器。全自动摩托车归属识别尚未实现；当前使用活动提示和用户确认，GMS 缺失时手动开始。没有绑定蓝牙、OBD、云端 OCR 或位置上传。

GitHub Actions 工作流已提供；本地构建成功不表示远端 workflow 或 Artifact 已执行。实机步骤见 DEVICE_ACCEPTANCE.md。

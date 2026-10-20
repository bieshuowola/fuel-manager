# 燃油与续航管理工具

一款手机端静态网页工具。录入总里程与实际加油升数，或者通过加油金额与参考油价估算加入升数；使用满油周期动态学习百公里油耗，并独立估算安全续航。

## 数据与油价

- 行驶里程、加油记录及设置只保存在浏览器本地（localStorage）；没有账户，也不把私人记录上传到 GitHub。
- 必须在实际加满油之后首次校准，后续录入里程和补油记录；页面**无法自动读取车辆实时油位或里程**。不能把估算续航当成真实油箱读数。
- 金额补录时读取 [今日油价](https://bieshuowola.github.io/today-fuel-price/) 的公开 JSON 文件 `data/latest.json`，默认按福建宁德 92 号汽油参考价换算；参考价不是加油站实际成交价，可能受优惠和公告数据时效影响。
- 金额估算的满油周期不纳入高可信度满油法油耗学习。
- 缓存页面可离线使用；断网无法刷新参考价，需要人工核对单价。

## 部署

GitHub 仓库 Settings → Pages → Build and deployment → Deploy from a branch → Branch `main` / `(root)` → Save。根目录必须包含 `index.html`、`sw.js`、`manifest.webmanifest`、`icon.svg`。

打开网页后可使用浏览器菜单“添加到主屏幕”。由于浏览器来源不同，本地 `file://` 与 GitHub Pages 的数据并不会自动迁移。请先在旧版导出 JSON 备份，再在新版导入并核对数据。浏览器清缓存或更换手机也可能丢失记录，请定期导出备份。
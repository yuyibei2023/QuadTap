# 四格自动校准 Android（第一版）

这是一个可继续构建的 Android Studio 项目。第一版包含：

- A/B/C/D 四格无障碍悬浮面板；
- 页面变化时扫描无障碍节点；
- 优先根据选项文字/控件边界进行点击；
- 无障碍节点未找到时，使用 Android 11+ 的屏幕截图能力 + ML Kit 中文 OCR 尝试识别；
- 所有识别均在设备本地进行；只有用户点击悬浮按钮时才尝试执行点击。

## 构建

1. 使用 Android Studio 打开此文件夹。
2. 安装 Android SDK Platform 35（或按本机环境调整 compileSdk）。
3. 同步 Gradle，等待 ML Kit 依赖下载完成。
4. 选择 `Build > Build Bundle(s) / APK(s) > Build APK(s)`。
5. APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 手机启用

1. 安装 APK 并打开「四格自动校准」。
2. 点击「开启无障碍服务」，在系统设置中启用「四格自动校准服务」。
3. 返回题目应用，四格面板应显示在屏幕右侧。
4. 点击 A/B/C/D 进行测试。

## 第一版限制（请先阅读）

- 目前是原型版本，尚未针对用户指定的驾考应用做真机适配测试。
- 许多应用的选项文字并非独立的 A/B/C/D 控件，或者文字与可点击区域分离；第一版可能无法准确找到选项。
- 当前 OCR 回退主要识别 A/B/C/D 以及“正确/错误”等明显文字，不会理解题意，也不会自动答题。
- Android 版本、厂商无障碍策略、应用自身的防截屏/防点击机制都可能影响功能。
- OCR 截图 API 需要 Android 11（API 30）或更新版本；旧系统仍可尝试无障碍节点定位。
- 请只在你有权操作的应用中使用，并先在非正式页面验证点击位置。

## 项目技术

- Java + Android AccessibilityService
- Android `takeScreenshot` API（API 30+）
- Google ML Kit Chinese Text Recognition

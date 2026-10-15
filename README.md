# QuadTap 四格映射（Android V1.0 原型）

QuadTap 是一个 Android 悬浮点击与选项位置识别原型。它尝试结合 Android 无障碍节点和设备本地 OCR，帮助用户将四格悬浮按钮映射到当前屏幕中识别到的选项位置。

## 当前包含

- A/B/C/D 四格悬浮面板；
- 无障碍节点扫描与目标边界定位；
- Android 11+ 屏幕截图能力和 ML Kit 中文 OCR 回退；
- GitHub Actions 自动构建 Debug APK 的工作流；
- GPL-3.0 许可证。

## 构建 APK

请查看 [`BUILD_APK_ON_GITHUB.md`](BUILD_APK_ON_GITHUB.md)。GitHub 仓库必须包含 `.github/workflows/build-apk.yml`，且应将 `QuadTap` 文件夹内的内容上传到仓库根目录，而不是只上传 ZIP 文件。

## 第一版限制

- 尚未在你的目标驾考应用和真实设备上验证；
- 不保证 OCR 能准确理解所有题型或识别所有选项；
- 不会自动判断答案，只在用户点击悬浮按钮时尝试执行对应点击；
- Android 系统版本、目标应用界面结构、无障碍策略和权限都会影响效果；
- 构建工作流尚需在 GitHub Actions 上实际运行验证。

请先在你有权操作的应用中进行安全测试，并在确认映射准确后再使用。

## 技术方向

- Java / Android SDK
- Android AccessibilityService
- Android 屏幕截图 API（API 30+）
- Google ML Kit 中文文字识别

## License

本项目采用 GNU GPL v3.0，详见 `LICENSE`。第三方依赖仍受其各自许可证约束。

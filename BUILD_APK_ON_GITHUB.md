# 通过 GitHub Actions 构建 APK

当前生成环境没有安装 Android SDK，且无法连接外网下载 Android/Gradle 依赖，因此不能在这里产出经过构建的 APK。项目已附带 `.github/workflows/build-apk.yml`，可以在 GitHub 云端构建：

1. 在 GitHub 新建一个空仓库。
2. 将本 ZIP 解压后的项目文件上传到仓库根目录并推送到 `main` 分支。
3. 打开仓库的 **Actions** 页面，选择 **Build Android APK**，点击 **Run workflow**（或等待 push 自动触发）。
4. 构建成功后，在对应运行记录页面底部的 **Artifacts** 下载 `QuadTap-debug-apk`，里面是 `app-debug.apk`。

这是 debug 测试包，不是 Play 商店发布签名包。安装时可能需要允许“安装未知应用”。

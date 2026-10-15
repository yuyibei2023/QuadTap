# 在 GitHub 上自动构建 APK

## 仓库文件上传要求

请先解压 `QuadTap-Android-Full-v1.zip`。进入解压后的 `QuadTap` 文件夹，将**里面的所有文件和文件夹**上传到 GitHub 仓库根目录。不要只上传 ZIP 文件，也不要漏掉隐藏目录 `.github`。

上传完成后，仓库根目录应至少能看到：

- `.github/workflows/build-apk.yml`
- `app/`
- `build.gradle`
- `settings.gradle`
- `gradle.properties`
- `LICENSE`

## 运行构建

1. 在 GitHub 仓库中打开 **Actions**。
2. 选择 **Build Android APK**。
3. 点击 **Run workflow**。如果没有显示该工作流，先确认 `.github/workflows/build-apk.yml` 已提交到默认分支。
4. 等待工作流结束，打开本次运行记录。
5. 在页面底部的 **Artifacts** 中下载 `QuadTap-debug-apk`。
6. 解压下载的 artifact，得到 `app-debug.apk`。

推送到 `main` 或 `master` 分支也会触发构建。工作流使用 GitHub 托管的 Ubuntu 环境、JDK 17、Android SDK 35 和 Gradle 8.9。

## 重要说明

这是第一版原型，工作流配置已提供，但仍需在 GitHub 上实际运行才能验证构建是否成功。若构建失败，请打开日志查看第一个 `FAILURE` 或 `What went wrong` 错误段落。

# 本地编译环境

本机原先没有任何 JDK、Android SDK 或 Gradle,CI 是唯一的编译器 —— 改一行代码要等
一次完整的 CI 往返(约 2 分钟),而且**编译错误只能靠猜**。

现在已经在本机装好一套完整工具链,**改完代码可以立刻编译验证**。

## 装在哪里

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `D:\android-toolchain\jdk\jdk17.0.20_12` | Amazon Corretto 17.0.20.1 |
| Android SDK | `D:\android-toolchain\sdk` | platform-35 + build-tools 35.0.0 |
| Gradle | `D:\android-toolchain\gradle\gradle-8.9` | 8.9 |

总计约 1.5 GB。都在 `D:\android-toolchain`,不在仓库里。

## 怎么编译

```powershell
$tc = 'D:\android-toolchain'
$env:JAVA_HOME        = "$tc\jdk\jdk17.0.20_12"
$env:ANDROID_SDK_ROOT = "$tc\sdk"
$env:ANDROID_HOME     = "$tc\sdk"
$env:PATH = "$env:JAVA_HOME\bin;$tc\sdk\platform-tools;$env:PATH"

cd D:\deepseekharness\QuadTap
& "$tc\gradle\gradle-8.9\bin\gradle.bat" --no-daemon assembleDebug
```

产物在 `app\build\outputs\apk\debug\app-debug.apk`。

首次编译约 3～4 分钟(要下载 AGP 和依赖),之后有缓存会快很多。

`local.properties` 里写了 `sdk.dir`,AGP 靠它找到 SDK。**这个文件已被 `.gitignore` 忽略**,
不会进仓库 —— 每个人的 SDK 路径不一样,本来就不该提交。

## 两个必须知道的坑

### 1. Gradle 发行包不能从官网下

`services.gradle.org` 和 `downloads.gradle.org` 都会 **307 重定向到 GitHub Releases**
(`github.com/gradle/gradle-distributions/...`),而 `github.com` 在本网络下是**不通的**,
所以官网下载必然失败(curl 报 `exit 56`)。

**改用国内镜像**,实测腾讯云可用而且快得多(130 MB / 4.6 秒):

```
https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
```

### 2. PowerShell 里下载大文件别用 WebClient

`System.Net.WebClient` 的默认超时是 **100 秒**。Gradle 那个 130 MB 的包用时超过 100 秒,
会直接报「操作超时」而不是继续下载。用 `curl.exe` 没有这个限制:

```powershell
curl.exe -L --retry 3 --max-time 2400 -o 目标文件 下载地址
```

## 顺带记一下:依赖下载源是通的

被墙的只有 `github.com`。构建体系需要的这些域名都能访问:

- `dl.google.com` —— Google Maven(AGP、androidx、ML Kit)
- `repo.maven.apache.org` —— Maven Central
- `mirrors.cloud.tencent.com` —— Gradle 发行包镜像

所以**依赖解析不会有问题**,只有 GitHub 托管的东西(Release 产物、Gradle 官方发行包)
需要绕。

## 注意

编译产物 `app\build\` 已被 `.gitignore` 忽略。但 `app\build\outputs\` 里会有 APK,
如果你在仓库目录里编译过,**提交前留意一下 `git status`**,别把构建产物带进去。

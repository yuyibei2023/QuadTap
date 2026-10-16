# 正式签名配置指南

## 为什么需要

Android 要求每个 APK 都带数字签名。现在构建出来的是 **debug 签名** —— 由 Android SDK 自动生成，
口令是公开的 `android`，**全世界所有开发者用的是同一把**。它只能证明"这包是某个开发工具打的"。

正式签名用**你自己生成的密钥**，私钥只有你有。

**最关键的一点**：Android 不允许用**不同密钥**签名的 APK 覆盖安装同一个应用。
所以如果先用 debug 分发、以后才换正式签名，用户必须**先卸载再装**，设置和校准数据会全部丢失。

**现在就配好，以后就能一直平滑升级。**

---

## 第 1 步：生成密钥库

只需要做**一次**。

你的机器上没有装 JDK，但 VERICUT 自带了一个 OpenJDK 21（实测可用），直接用它的 `keytool` 就行：

```powershell
# 建议放在仓库外面，避免被误提交
mkdir D:\keys

& "D:\Program Files\CGTech\Vericut 9.6\windows64\jre\bin\keytool.exe" `
  -genkeypair -v `
  -keystore D:\keys\quadtap-release.jks `
  -storetype PKCS12 `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -alias quadtap `
  -storepass 你的口令 `
  -dname "CN=QuadTap, O=Personal, C=CN"
```

把 `你的口令` 换成一个你自己设的强口令。其余参数照抄。

如果你以后装了 JDK，把 `keytool.exe` 的路径换成 `keytool` 即可，命令完全一样。

**`-storetype PKCS12` 和 `-alias quadtap` 不要改**：前者对应下文的密码规则，后者是密钥别名。

> 实测提示：PKCS12 格式**不支持两套不同的密码**。如果你另外传 `-keypass`，keytool 会提示
> `Different store and key passwords not supported for PKCS12 KeyStores. Ignoring user-specified -keypass value.`
> 并忽略它。**所以仓库口令和密钥口令必须填同一个值**（见第 3 步）。

生成后会得到 `D:\keys\quadtap-release.jks`，大约 2–3 KB。

## 第 2 步：转成 base64

GitHub 的 Secret 不能直接存二进制文件，要转成文本：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\keys\quadtap-release.jks")) `
  | Set-Content -NoNewline D:\keys\keystore.b64

# 复制内容备用（这一步只是让你拿到那串字符）
Get-Content D:\keys\keystore.b64 | Set-Clipboard
```

`D:\keys\keystore.b64` 里是**一整行**字符，不要手动换行。

## 第 3 步：在 GitHub 添加 4 个 Secrets

打开 https://github.com/yuyibei2023/QuadTap/settings/secrets/actions
点 `New repository secret`，逐个添加：

| Name | Value |
|---|---|
| `QUADTAP_KEYSTORE_BASE64` | `D:\keys\keystore.b64` 的全部内容（一整行） |
| `QUADTAP_KEYSTORE_PASSWORD` | 第 1 步设的口令 |
| `QUADTAP_KEY_ALIAS` | `quadtap` |
| `QUADTAP_KEY_PASSWORD` | **和第 1 步同一个口令**（PKCS12 不允许不同） |

四个必须全部填。**少填任何一个，发布会直接失败并明确告诉你缺哪个**（而不是悄悄产出 debug 包）。

## 第 4 步：备份密钥（最重要的一步）

> **`quadtap-release.jks` 丢了，你就永远无法再更新这个应用了** —— 只能换包名重新发一个。

请至少备份到两个地方：网盘、U 盘、密码管理器附件等。**同时记好那个口令**，
只有密钥文件、没有口令同样打不开。

## 不要做的事

- **不要把 `.jks` / `.keystore` 提交进仓库**。`.gitignore` 里已经加了 `*.jks` 和 `*.keystore` 兜底，
  但别去挑战它
- **不要把口令写在任何会被提交的文件里**，也不要发到聊天里
- **不要在 CI 里打印密钥内容**。你的仓库是公开的，Actions 日志谁都能看。
  本仓库的流程只把密钥写到运行器的临时目录，日志里不会出现

---

## 配好之后会怎样

| | 之前 | 之后 |
|---|---|---|
| 产物名 | `QuadTap-v1.5-debug.apk` | `QuadTap-v1.6-release.apk` |
| 签名 | debug（人人可伪造） | 你的密钥 |
| 构建任务 | `assembleDebug` | `assembleRelease` |
| CI 校验 | 无 | `apksigner verify --print-certs` 打印签名证书 |

流程会自动判断：**读得到 `QUADTAP_KEYSTORE_BASE64` 就用正式签名，读不到就回退 debug 签名。**
所以配置之前你的构建一直是好的，不会因为我这次改动而变坏。

## 怎么确认生效了

发一个版本，然后看运行日志里的这几行：

```
已还原签名密钥，本次构建正式签名的 APK。
签名方式 = release
正在校验 QuadTap-v1.6-release.apk
Signer #1 certificate DN: CN=QuadTap, O=Personal, C=CN
```

看到 `Signer #1 certificate DN` 是你的 `CN=QuadTap` 就说明签名生效了。
如果是回退状态，日志里会是 `未配置签名密钥，本次构建 debug 签名的 APK`。

## 本地构建怎么用

CI 从环境变量读这四个值，本地也可以照做：

```powershell
$env:QUADTAP_KEYSTORE_FILE     = "D:\keys\quadtap-release.jks"
$env:QUADTAP_KEYSTORE_PASSWORD = "你的口令"
$env:QUADTAP_KEY_ALIAS         = "quadtap"
$env:QUADTAP_KEY_PASSWORD      = "你的口令"
gradle assembleRelease
```

不设这些变量时 `assembleRelease` 会产出**未签名**的 APK（AGP 的正常行为），
日常调试用 `assembleDebug` 即可。

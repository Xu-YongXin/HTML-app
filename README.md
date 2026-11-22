# HTML 预览器（Android）

把 DeepSeek 生成的 HTML 代码粘进来 → 自动清洗并转成标准页面 → 手机上直接预览，或一键丢进系统浏览器打开。
专治「DeepSeek 生成的 HTML 在手机上只能看到代码、显示不出来」。

```
复制代码  →  粘贴进 App  →  自动转成真正的 HTML  →  预览 / 浏览器打开 / 保存
```

---

## 它能做什么

| 能力 | 说明 |
| --- | --- |
| 自动清洗 | 去掉 ```html 代码围栏、去掉「以下是代码」之类的说明文字，只留页面本体 |
| 自动补全 | 补 `<!DOCTYPE>`、`<meta charset>`、手机 `viewport`；只有片段时自动补 `<html><head><body>` 骨架 |
| 纯文本兜底 | 如果不是 HTML（比如一段说明文字），按排版好的纯文本显示，不会白屏 |
| 应用内预览 | iframe 沙箱内运行，脚本、动画、CDN 外链都能跑 |
| **浏览器打开** | 一键交给 Chrome / Edge / 夸克等系统浏览器。App 会在 `127.0.0.1` 上起一个临时 HTTP 服务，所以浏览器拿到的是**真正的 http 页面**，localStorage、相对路径嗅探、Worker 等行为与线上一致 |
| 保存 | 存成 `.html` 到 `下载/HtmlPreviewer/`，可在文件管理器里找到、再次打开 |
| 分享 | 把页面作为 `.html` 文件分享到微信 / QQ / 网盘等 |
| 接收分享 | 在 DeepSeek 里长按选中整段代码 → 系统「分享」→ 选本 App，直接接收 |
| 历史记录 | 自动记录最近 30 条打开过的页面，随时回看 |

> 为什么不是「导出文件再让浏览器打开」？Android 上跨 App 传 `file://` 会被系统拦截，浏览器对 `content://` 的 HTML 支持也不稳定。本地 HTTP 是最可靠的方案。

---

## 已经构建好的安装包

本目录下的 **`dist/app-debug.apk`**（约 70 KB）是已签名的调试版安装包，可以直接发到手机安装，先跑起来看看：

```powershell
# 电脑连了手机（需要 adb）时：
# adb install -r dist\app-debug.apk
# 或者直接把 dist\app-debug.apk 发到微信「文件传输助手」，手机上点开安装
```

想自己重新编译（云端/本地）见下面两节。

---

## 一、用 GitHub 云端编译 APK（推荐，不需要装 Android Studio）

### 1. 建仓库并推送

在电脑上本目录执行（把地址换成你自己的仓库）：

```bash
git init
git add .
git commit -m "init: HTML 预览器"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

### 2. 等 Actions 编译

打开仓库 → **Actions** → 左侧 **Build APK** → 最新一次运行，约 2~4 分钟完成。

### 3. 拿安装包（两种方式）

**方式 A（推荐，手机上最省事）：打 tag 自动发 Release**

```bash
git tag v1.0.0
git push origin v1.0.0
```

Actions 会自动创建 Release 并把 APK 挂到附件。手机浏览器打开 `https://github.com/<用户名>/<仓库名>/releases`，点 APK 直接下载安装。

**方式 B：从构建产物里拿**

这次运行页面底部 **Artifacts** → 下载 `HtmlPreviewer-debug-apk.zip` → 解压得到 `.apk`（手机上解压略麻烦，建议用方式 A）。

### 4. 安装

下载后用文件管理器点开安装，首次需要允许「安装未知应用 / 未知来源」。

---

## 二、本地编译（可选）

**用 Android Studio（最省心）**：直接 `Open` 本目录，等 Gradle Sync 完成后点 Run。

**用命令行（不需要 Android Studio）**：先装好 JDK 17 与 Android SDK（`sdkmanager "platforms;android-34" "build-tools;34.0.0"`），然后：

```powershell
# Windows PowerShell，路径按你本机改
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
./gradlew :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

树莓派/无网络环境也可用 `tools/build-local.ps1`（纯 aapt2 + javac + d8 + apksigner，产物在 `dist/`）。

---

## 三、怎么用

1. **DeepSeek 里复制代码**：长按代码块 → 全选 → 复制；或长按 → 分享 → 选「HTML 预览器」。
2. 打开 App → 点 **粘贴**（App 会自动读剪贴板，不用手动长按）。
3. 输入框下方会显示识别结果：`完整页面 / HTML 片段 / 纯文本`，以及做了哪些自动修复。
4. 切到 **预览效果** 看渲染结果。
5. 点 **浏览器打开** → 选 Chrome 等浏览器，得到全屏、可交互的真实页面。
6. 需要留存就点 **保存**（存到「下载」）或 **分享**（发到微信 / 电脑）。

---

## 四、实现说明

```
app/src/main/
├─ assets/www/index.html          # 全部界面与转换逻辑（单文件，无框架）
├─ java/com/dsh/htmlviewer/
│  ├─ MainActivity.java           # WebView 外壳、接收分享/打开文件
│  ├─ AndroidBridge.java          # window.Android：剪贴板 / 浏览器 / 保存 / 分享
│  ├─ LocalWebServer.java         # 127.0.0.1 临时 HTTP 服务（交给系统浏览器）
│  └─ ShareFileProvider.java      # 把 .html 以 content:// 分享出去
└─ res/                            # 主题、图标
```

- 零第三方依赖（不引 AndroidX / Kotlin），APK 只有几百 KB，构建十几秒。
- 界面逻辑全在 `index.html`，改样式/改转换规则不用碰 Java，重装即可，也可直接在电脑浏览器里打开调试。
- `minSdk 24`（Android 7.0）～ `targetSdk 34`。

---

## 五、常见问题

**1. 浏览器打开后是空白 / 无法访问此网站**
本地服务依赖 App 进程存活。把 App 从最近任务里划掉后浏览器会打不开，回到 App 重新点一次「浏览器打开」即可。地址栏里的 `127.0.0.1:872x` 是正常的。

**2. 页面里的相对路径资源（`./a.png`、`style.css`）加载不出来**
粘贴进来的是纯文本，只有一个页面文件，没有配套的图片/CSS。用 CDN 外链（`https://...`）的页面完全没问题。

**3. 安装时提示「应用未安装 / 已被拦截」**
在系统设置里允许文件管理器/浏览器「安装未知应用」，或先关掉手机管家的安装拦截。

**4. 预览里某些功能不工作**
预览用的是 iframe 沙箱（允许脚本、允许同源），个别依赖 `fetch` 本地文件的页面可能受限，用「浏览器打开」看最终效果。

**5. iOS 能用吗**
不能。iOS Safari 不允许把「内存里的 HTML」交给另一个 App 渲染，这也是这个 App 只做 Android 的原因。

**6. 想改包名 / 版本号**
改 `app/build.gradle` 里的 `applicationId`、`versionCode`、`versionName`。

**7. 想发正式签名包**
本地生成 keystore，然后：

```powershell
$env:KEYSTORE_PATH = "C:\keys\release.jks"
$env:KEYSTORE_PASSWORD = "***"
$env:KEY_ALIAS = "release"
$env:KEY_PASSWORD = "***"
./gradlew :app:assembleRelease
```

（工作流里也可以加 `KEYSTORE_BASE64` 等 Secrets 走 CI 签名，需要的话我可以补。）

---

## 六、目录结构

```
.
├─ .github/workflows/build-apk.yml   # 云端编译 + 打 tag 自动发 Release
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/main/{AndroidManifest.xml, assets, java, res}
├─ gradle/wrapper/                   # Gradle 8.9 wrapper
├─ build.gradle / settings.gradle / gradle.properties
├─ tools/generate_icons.py           # 图标生成（改配色用）
└─ docs/icon-512.png
```

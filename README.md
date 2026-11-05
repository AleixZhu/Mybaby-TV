# 儿童看电视管家（KidsTvGuard）

在智能电视（Android TV / 厂商定制 Android）上运行的家长管控 APP。孩子看视频 APP 到设定时间就弹题；答对继续看，答错加题，最多加到 5 道；关机再开机若上次没答完，立即补弹。

## 覆盖的需求

| 需求 | 实现 |
| --- | --- |
| 每 5/10/15/20/30 分钟弹题 | 设置页 RadioGroup 五档，值持久化到 `SharedPreferences` |
| 答对可继续看电视 | 悬浮窗关闭 → `GuardService` 重置计时 |
| 答错加一道题，最多 5 道 | `QuizOverlayController.onAnswer` 累加 `wrongStreak`，动态扩队列到 `1+wrongStreak`，封顶 `maxQuestionsPerRound=5` |
| 弹窗开关可配置 | 设置页顶部 Switch，写入 `popupEnabled` |
| 修改弹窗时长需要密码 | 进入设置页整体走密码门（`dialog_password.xml`）；默认密码 `1234`，可在设置页最下方修改；密码用 SHA-256 加盐存 SP |
| 分年级题库，设置页可选年级 | 设置页"当前年级"卡片，`-/+` 步进切换 0=不区分 / 1–6 小学 / 7–9 初中；题目 JSON 支持 `"grade": 3` 或 `"grades": [1,2,3]` 字段；远程 URL 支持 `{grade}` / `{N}` 模板，切换年级自动重新拉取 |
| 弹题时题目随机 | `QuestionRepository.pickRandom` 用 `System.nanoTime()` 做种子洗牌，优先抽符合当前年级的题，同轮内按题干去重（`usedStems`），池不够时补通用题 |
| 每题 60 秒倒计时 | 悬浮窗顶部显示"剩余 X 秒"，每 100ms 刷新；≤10 秒时字体加粗变红（`#FF1744`）；到点视为答错，`wrongStreak++` 后进入下一题（连错到上限才锁死等家长） |
| 未答题就关机 → 开机立即弹 | 弹题开始即置 `pendingPopupOnBoot=true` + `pendingWrongStreak`，成功结束时清 false；`BootReceiver` 收 `BOOT_COMPLETED` 拉起 `GuardService`，Service `onCreate` 读到 true 立即弹，且沿用关机前的连错次数继续出题 |

## 关键技术边界（务必看）

**HDMI 输入无法叠加弹窗。** Android TV 上 HDMI/AV 信号走硬件视频层，第三方 APP 无系统签名就不能在其上绘制窗口，这是系统级限制，不是代码问题。

本 APP 采取的策略：

- **前台是 Android 视频 APP**（爱奇艺/腾讯/优酷/B站/YouTube/Netflix 等，白名单在 `UsageStatsHelper.VIDEO_PACKAGES`）→ 正常累计观看时长，到点弹题。
- **前台是 HDMI/直播信号** → 无法用 `UsageStatsManager` 识别；计时暂停，等孩子切回任意 Android 视频 APP 前台的瞬间立即继续计时。
- **上锁场景**（连错 5 次或单题超时）→ 悬浮窗持续显示，需要家长输入密码才能关闭，即使孩子切走 APP，回到桌面/任何前台都会看到悬浮窗（Android 系统层特性）。

**要真在 HDMI 上弹题**，只能走下列路子：找电视厂商拿平台签名，把 APP 作为系统应用预装，用 `TYPE_SYSTEM_ERROR` 或私有 HDMI 叠加接口；或者用带 HDMI-CEC 阻断能力的外接盒子。普通用户侧无解。

## 目录结构

```
KidsTvGuard/
├── build.gradle.kts              # 顶层构建（AGP 8.5.2 / Kotlin 1.9.24）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties  # Gradle 8.7
└── app/
    ├── build.gradle.kts          # minSdk 24 / targetSdk 34
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/questions.json # 20 道内置示例题
        ├── java/com/kidstvguard/
        │   ├── data/
        │   │   ├── Question.kt
        │   │   ├── PrefsManager.kt          # 设置+密码+断电续弹状态
        │   │   └── QuestionRepository.kt    # 内置+外部 JSON 加载/导入
        │   ├── overlay/
        │   │   └── QuizOverlayController.kt # 悬浮答题窗状态机
        │   ├── service/
        │   │   ├── GuardService.kt          # 前台服务+计时+触发
        │   │   ├── GuardServiceRunning.kt
        │   │   └── BootReceiver.kt          # 开机广播
        │   ├── ui/
        │   │   ├── MainActivity.kt          # 状态面板+权限引导
        │   │   └── SettingsActivity.kt      # 密码门+全部配置项
        │   └── util/
        │       └── UsageStatsHelper.kt      # 前台包检测+视频白名单
        └── res/
            ├── layout/          # activity_main / activity_settings / dialog_password / view_quiz_overlay
            ├── drawable/        # 面板/按钮/输入框/图标/TV banner
            └── values/          # strings / colors / themes / styles
```

## 编译成 APK：三条路径

### 路径 1：GitHub Actions 云端自动编（零本地配置，推荐首次使用）

工程已内置 `.github/workflows/build.yml`。你只要把代码推到 GitHub，Actions 会自动用 ubuntu-latest + JDK 17 + Android SDK 编出 debug APK。

**步骤**：

1. 在 GitHub 新建一个仓库（公开或私有都行），比如 `kids-tv-guard`
2. 本地把工程推上去：
   ```bash
   cd KidsTvGuard
   git init
   git add .
   git commit -m "init: KidsTvGuard v1.2"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/kids-tv-guard.git
   git push -u origin main
   ```
3. 打开仓库页面 → `Actions` 标签 → 看到 "Build APK" workflow 正在跑（约 5–8 分钟）
4. 跑完后点进 workflow run → 页面底部 `Artifacts` 区域 → 下载 `KidsTvGuard-debug-apk`
5. 解压得到 `app-debug.apk`，拷到 U 盘或直接用 adb 装到电视

**优点**：不用装 JDK、不用装 Android Studio、不用配 SDK，完全云端。
**费用**：GitHub Actions 公开仓库免费无限；私有仓库每月 2000 分钟免费额度，够用。
**Gitee 用户**：Gitee Go 也支持类似流水线，但语法不同；最简单的办法是镜像同步到 GitHub 跑 Actions。

### 路径 2：Android Studio 一键 Build（推荐长期开发）

**前置**：
- 下载 Android Studio Koala 或更新版本：https://developer.android.com/studio
- 首次启动会引导下载 Android SDK 34（约 2GB）

**步骤**：

1. 打开 Android Studio → `File → Open` → 选 `KidsTvGuard` 目录
2. 等 Gradle Sync 完成（首次约 3–5 分钟，会自动下载依赖）
3. 菜单 `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 编译完成后点通知里的 `locate` 链接，或手动到 `app/build/outputs/apk/debug/app-debug.apk` 取

**优点**：有 IDE 可以改代码、调试、看日志；增量编译很快（10 秒级）。
**缺点**：首次下载 Android Studio + SDK 约 3GB，需要耐心。

### 路径 3：纯命令行 + build.sh 辅助脚本（推荐极客）

工程根目录有 `build.sh`，会自动检查 JDK / SDK、生成 wrapper、编译 APK。

**前置**：

```bash
# macOS
brew install --cask temurin@17          # JDK 17
brew install gradle                      # Gradle（用于首次生成 wrapper）
# Android SDK：装 Android Studio 或用 sdkmanager 命令行装
#   命令行装法：
#   brew install --cask android-commandlinetools
#   sdkmanager "platforms;android-34" "build-tools;34.0.0"

# 设置环境变量（加到 ~/.zshrc 或 ~/.bashrc）
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

**编译**：

```bash
cd KidsTvGuard
./build.sh
```

脚本会依次：
1. 检查 `java` 是否 ≥17
2. 检查 `ANDROID_HOME` 或常见 SDK 路径
3. 生成 `local.properties`
4. 若没有 `gradlew` 则用系统 `gradle wrapper --gradle-version 8.7` 生成
5. 跑 `./gradlew assembleDebug --no-daemon`
6. 输出 APK 路径和大小

**手动编译**（不用 build.sh）：

```bash
cd KidsTvGuard
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle wrapper --gradle-version 8.7    # 首次需要，生成 gradlew + wrapper jar
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

**Release 签名版**（可选）：

```bash
# 生成签名密钥（一次性）
keytool -genkey -v -keystore kids-tv-guard.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload

# 在 app/build.gradle.kts 里加 signingConfigs，然后
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 三条路径对比

| 路径 | 本地要装 | 首次耗时 | 之后耗时 | 适合谁 |
| --- | --- | --- | --- | --- |
| GitHub Actions | 只要 git | 5–8 分钟（云端） | 5–8 分钟（每次 push） | 不想装任何开发工具的人 |
| Android Studio | AS + SDK 约 3GB | 10–15 分钟 | 10–30 秒（增量） | 要改代码、长期维护 |
| build.sh 命令行 | JDK + Gradle + SDK | 10–15 分钟 | 30 秒–2 分钟 | 极客、CI 脚本、服务器编译 |

**我的推荐**：先用路径 1 拿到第一个 APK 装到电视验证功能，确认没问题后再决定要不要本地装 Android Studio（路径 2）做后续迭代。

## 安装到电视

三种方式，任选：

1. **ADB 推**：电视开启"开发者选项 → ADB 调试"，然后
   ```bash
   adb connect <电视 IP>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **U 盘**：把 APK 拷进 U 盘，插电视，用电视自带文件管理器安装（需要允许"未知来源"）。
3. **局域网传输**：用 `Send Files to TV` 之类的 APP 推。

## 首次使用（权限三连）

打开 APP → 主页三个权限按钮依次点亮：

1. **悬浮窗权限** — 弹题必需，跳系统"显示在其他应用上层"页面，允许即可。
2. **使用情况访问** — 检测前台是不是视频 APP，跳系统"使用情况访问"，找到本 APP 开启。
3. **通知权限**（Android 13+） — 前台服务通知需要，弹窗允许即可。

三个都通过后点"启动守护"。通知栏会出现"看电视守护运行中"。可以先点"立即试弹一次"验证悬浮窗效果。

## 自定义题库

内置 20 道示例题（小学数学+常识）。替换成自己的题库有两种方式：**本地导入**（一次性）和**远程 URL**（异地维护，见下一节）。

**JSON 格式**（两种方式共用）：

```json
[
  {
    "stem": "题干",
    "options": ["选项1", "选项2", "选项3", "选项4"],
    "answer": 0,
    "explain": "答错时展示的解释，可选",
    "grade": 3
  },
  {
    "stem": "1+1=?",
    "options": ["1", "2", "3", "4"],
    "answer": 1,
    "grades": [1, 2]
  },
  {
    "stem": "中国的首都是？",
    "options": ["上海", "北京", "广州", "深圳"],
    "answer": 1
  }
]
```

字段说明：

- `answer` 是正确选项索引（0 起）。
- `options` 长度 2–4。
- `explain` 可选，答错时展示。
- `grade` 单值 或 `grades` 数组，二选一即可；两个都写会合并。数字范围 1–9（1–6 小学，7–9 初中）。
- **不写 grade/grades 字段的题目视为"通用题"，任何年级都会命中**（例如上面第三题"中国的首都"）。
- 当前年级为 0（不区分）时，所有题都会命中，不过滤。

## 分年级题库：两种组织方式

### 方式 A：单 JSON + grade 标签（推荐入门）

一份 `questions.json` 装所有年级的题，每题打上 `grade` 或 `grades` 标签。APP 按"当前年级"过滤。

**优点**：只需维护一个文件，改题目不用切换 URL。
**缺点**：文件会越来越长；所有年级题目都下载到本地。

**Gitee 上的目录结构**：

```
kids-tv-bank/
└── questions.json     ← 一份文件，内部按 grade 区分
```

APP 设置：远程 URL 填 `https://your-name.gitee.io/kids-tv-bank/questions.json`（不含模板占位符）。

### 方式 B：URL 模板 + 每年级独立文件（推荐规模化）

每个年级一个 JSON 文件，URL 里带 `{grade}` 或 `{N}` 占位符。切换年级时 APP 自动重新拉取对应文件，缓存也按年级分开存（`remote_questions_g{N}.json`）。

**优点**：文件小、按需下载、可以请不同老师维护不同年级。
**缺点**：切换年级需要网络（首次）。

**Gitee 上的目录结构**：

```
kids-tv-bank/
├── grade0.json    ← 通用/不区分
├── grade1.json
├── grade2.json
├── grade3.json
├── grade4.json
├── grade5.json
├── grade6.json
├── grade7.json
├── grade8.json
└── grade9.json
```

APP 设置：远程 URL 填 `https://your-name.gitee.io/kids-tv-bank/grade{grade}.json`。当前年级是三年级 → APP 实际请求 `grade3.json`；切到"不区分" → 请求 `grade0.json`。

### 两种方式可以混用

比如 URL 用模板（方式 B），但每个 `gradeN.json` 内部也可以再给题目打 `grades` 标签做二次过滤——APP 会先按 URL 拉当前年级的文件，然后再用 `matchesGrade(grade)` 过滤一遍。不冲突。

### 切换年级会发生什么

设置页 → "当前年级" 卡片 → 点 `-/+` 切换到目标年级：

1. `prefs.selectedGrade` 立刻更新
2. `QuestionRepository.reload()` 优先读该年级的远程缓存
3. 主页和设置页的"题库：X 道题（当前年级命中 Y）"刷新
4. 若配置了远程 URL → `GuardService.syncNow` 触发后台拉取该年级的 JSON
5. 下次弹题按新年级抽题

**本地导入方式**：

- 设置页 → "导入 JSON 题库" → 系统文件选择器选一个 `.json` 文件（推荐先用 U 盘/微信传到电视的 Download 目录）。
- 也可以直接把文件命名为 `kids_tv_guard_questions.json` 放到 `/sdcard/Download/`，APP 每次刷新题库时会自动读取。
- 想恢复内置示例：设置页 → "恢复内置题库"。

## 远程题库：家长异地维护（推荐）

**场景**：题库维护者（家长）和电视不在同一个地方，希望通过网页/手机随时改题目，电视自动同步。

**做法**：把 `questions.json` 放到任意可公网访问的地方，然后在 APP 设置页 → "远程题库 URL" 里填这个地址，点"保存 URL 并立即同步"。

**加载优先级**：远程缓存 → SAF 本地导入 → `/sdcard/Download/` → 内置示例。远程拉取失败不覆盖旧缓存，断网也能用上次的题目。

**同步时机**：
- `GuardService` 启动后 3 秒拉一次
- 之后按"自动同步周期"定时拉（可选：仅启动时 / 1 / 6 / 12 / 24 / 48 / 72 / 168 小时）
- 设置页手动点"立即同步"

**免费托管方案对比**：

| 方案 | 免费额度 | 国内访问 | 编辑方式 | 推荐指数 |
| --- | --- | --- | --- | --- |
| **Gitee Pages** | 无限（个人实名） | 快 | Git 提交 / 网页编辑 | ★★★★★ |
| **阿里云 OSS** | 40GB 存储 + 5GB/月流量（新用户 12 个月） | 快 | 控制台上传 / OSS Browser | ★★★★★ |
| **腾讯云 COS** | 50GB 存储 + 10GB/月流量（新用户 6 个月） | 快 | 控制台上传 | ★★★★ |
| **Cloudflare Pages** | 无限带宽 | 中等 | Git 集成 / 直接上传 | ★★★★ |
| **GitHub Pages** | 无限（公开仓库） | 慢/偶尔被墙 | Git 提交 / 网页编辑 | ★★★ |
| **Vercel** | 100GB/月 | 中等 | Git 集成 | ★★★ |

**Gitee Pages 最简流程**（推荐首次使用）：

1. 注册 Gitee → 新建仓库 `kids-tv-bank`（公开）
2. 仓库里新建文件 `questions.json`，粘贴你的题目，提交
3. 仓库 → 服务 → Gitee Pages → 启动，得到地址 `https://your-name.gitee.io/kids-tv-bank/`
4. 电视 APP 里填 `https://your-name.gitee.io/kids-tv-bank/questions.json`
5. 以后改题目：Gitee 网页上直接编辑 `questions.json` → 提交 → Gitee Pages 页面点"更新" → 电视等到下次自动同步周期或手动点"立即同步"

**阿里云 OSS 最简流程**（不需要 Git）：

1. 开通 OSS → 创建 Bucket（读写权限设"公共读"）
2. 上传 `questions.json` 到 Bucket
3. 复制文件的"URL"（形如 `https://your-bucket.oss-cn-hangzhou.aliyuncs.com/questions.json`）
4. 填到 APP 里
5. 以后改题目：控制台"文件管理" → 下载 → 改 → 覆盖上传（或用 OSS Browser 客户端）

## 部署方案：需要买服务器吗？

**结论：家用完全不用买服务器。** 拆两件事看：

### 一、APK 分发（怎么把安装包弄到电视上）

| 方案 | 成本 | 适用场景 |
| --- | --- | --- |
| **ADB 直装** | 0 | 家和维护点在同一 LAN，一次性装完就不用管 |
| **U 盘拷贝** | 0 | 家用最简单，电视装个文件管理器就能装 |
| **Gitee Releases** | 0 | 异地分发，家长发链接给家里人，电视浏览器下载安装 |
| **GitHub Releases** | 0 | 同上，国内下载速度偶尔慢 |
| **阿里云 OSS / 腾讯云 COS** | 免费额度足够 | 挂个 APK 下载链接，扫码装 |
| **自建 Nginx VPS** | 60–100 元/月 | 有多套设备/多家庭批量部署需求才考虑 |
| **当贝市场/沙发管家上架** | 需审核 | 面向公众发布才走这条路 |

**家用推荐组合**：Gitee 仓库存源码 + Gitee Releases 挂 APK + Gitee Pages 挂 questions.json，一分钱不花。

### 二、题库/配置数据服务

| 方案 | 成本 | 何时选 |
| --- | --- | --- |
| **静态 JSON + Pages/OSS**（当前实现） | 0 | 单家庭或几个家庭共用一份题库 |
| **Serverless 云函数**（腾讯云 SCF / 阿里云 FC / Cloudflare Workers） | 免费额度：每月百万次调用 | 需要动态生成题目、按孩子年龄分层、多用户 |
| **传统 VPS + Nginx/Node/Python** | 60–200 元/月 | 要做完整后台管理系统、账号体系、数据看板 |
| **微信小程序云开发** | 免费额度够家用 | 家长想通过小程序维护题库 |

**当前 APP 只需要"静态 JSON"这一档，Gitee Pages / OSS 免费额度足够用几年**。除非你要做下面这些事，才需要升级：

- 多个家庭独立管理各自的题库和观看数据 → 需要后端 + 数据库 + 账号体系
- 家长在手机小程序上实时看孩子答题记录 → 需要后端 API + 数据上报
- 根据孩子年级/学科动态出题 → 需要 Serverless 或后端逻辑
- 大量电视批量部署（比如托管班、亲戚朋友圈几十台） → 需要企业签名 + MDM 或自建 APK 分发平台

### 三、真需要买服务器时的最低配推荐

如果确实要自建后端：

- **阿里云/腾讯云轻量应用服务器**：2 核 2G 40GB SSD，年付 60–120 元，够用
- **域名 + ICP 备案**：域名 30–60 元/年，备案免费但要 15 个工作日
- **HTTPS 证书**：Let's Encrypt 免费，或云厂商单域名证书 0–1000 元/年
- **对象存储 + CDN**：按量付费，家用量级 <5 元/月

一年下来 100–300 元就能跑得很稳。但**再强调一次**：家用场景 Gitee Pages 完全够用，不用花这个钱。

## 默认密码 & 修改

- 默认密码：`1234`
- 修改：设置页最下方，输入当前密码 + 新密码（4–16 位数字）→ 保存新密码。
- 密码 SHA-256 加盐存本地，忘了密码只能卸载重装（`adb uninstall com.kidstvguard`）。

## 需求 4 的验证方法（断电续弹）

1. 主页点"立即试弹一次"，弹出答题窗。
2. **不作答**，直接长按电视电源键关机（或拔电）。
3. 开机 → 通知栏出现"看电视守护运行中" → 约 1.5 秒后答题窗立即弹出。

原理：`show()` 时写 `pendingPopupOnBoot=true`，`finishRound()` 时写 false。中途断电，标记保留在 SharedPreferences 里。`BootReceiver` 收到 `BOOT_COMPLETED` 拉起 `GuardService`，Service `onCreate` 读到 true 就调用 `controller.show(bootPending=true)`，并且沿用关机前的 `wrongStreak`，孩子接着答剩下的题。

## 已知的取舍

- **前台是桌面/设置时不计时**：避免家长自己用电视时被误伤；孩子切桌面逃避弹题也不会清零，切回视频 APP 会接着算。
- **倒计时到点视为答错**：单题 60 秒（可调）内没按选项，按答错处理，`wrongStreak++` 后进入下一题；只有连错达到上限（默认 5 次）才锁死悬浮窗等家长密码。这样孩子走神一两次不至于直接被锁。
- **锁死场景需要家长密码**：防止孩子通过"故意答错到底"绕过；密码门在悬浮窗内直接输入，不用退出。
- **年级过滤有回退**：当前年级没有匹配题时，会自动退回全部题目池，避免"切到某个年级后弹不出题"。
- **服务被系统杀**：Android TV 后台管理严格的机型可能会杀前台服务；`GuardService` 用 `START_STICKY`，被杀后系统会尝试重启，且 `pendingPopupOnBoot` 状态保留。
- **多用户/儿童模式**：目前没有做用户区分，全设备生效。

## 后续可扩展（当前未实现）

- 按 APP 白名单精细化（比如只对爱奇艺生效，YouTube 放过）
- 答题正确率统计与周报
- 远程配置（家长手机扫码下发题库/时长）
- 语音读题（TTS）
- 支持触屏（当前只针对遥控器 DPAD 优化，触屏也能用）

## 版本

- 1.0.0 — 首版，覆盖需求 1–4。

# NanoKVM-Pro Android — 开发文档

本文件沉淀当前进度、构建/签名/发布方法、真机与模拟器测试方法、已知坑。
项目根:本仓库;设计规范见仓库根说明与 `.workbench/spec.md` 思路(连接页/主控台/助手/工具箱均按 finesse-brief 方法定义后实现)。

## 1. 当前进度总览(2026-09-06)

### 已实现并真机/AVD 验证
- 远程桌面:H.264/H.265 × 直连/WebRTC 四通道、4K 渲染、旋转/缩放、触屏鼠标(绝对/相对/双指滚轮)、虚拟键盘、快捷键、诊断面板(码率/帧率/延迟曲线,uPlot 风格)
- 智能助手(cua 设备端 AI):App 内对话、实时桌面缩略图(MJPEG)、任务控制、模型设置、**会话接管**(客户端杀旧起新,免重启设备)
- 工具箱双页:「操作」(终端/串口/粘贴/快捷键/画面与鼠标/电源)与「设备管理」(设备信息/设备设置/显示·时间·账户/虚拟设备/Tailscale/系统更新/EDID/镜像/脚本/WOL/设备监控)
- 设备监控:CPU/内存/温度/负载 2s 采样、固定窗口滚动曲线、1/2/5/10s 间隔可调 + 更新 toast
- 主题:深浅色双套(DotGrid/图标 contentColor 随主题)+ 全局切换按钮(连接页右上、控制台顶栏)
- 平板自适应:横屏双栏(真横屏 ≥600dp 且宽>高),竖屏/手机堆叠;画面条带自适应

### 重要修复记录
- WebRTC 真机(arm64)闪退:**R8 shrink 导致 libjingle JNI_OnLoad SIGTRAP** → proguard 固定 `-dontobfuscate -dontoptimize -dontshrink`(详见 §3)
- Compose 布局坑:Row 内 **weight 之后的行内兄弟不渲染**(双栏空白、顶栏图标消失同源)→ 用「weight 容器在前 + 无 weight 兄弟殿后」规避
- pty 回显竞态:终端 WS 取数需等结束标记后 600ms 再收尾(否则解析空)
- 上传 Cursor 未 moveToFirst → CursorIndexOutOfBounds(两处同源已修)
- 深浅色图标黑对黑:根因无 Surface 时 LocalContentColor 恒黑 → Theme 层按 scheme 提供
- DotGridBackground 曾硬编码浅色 → 改随 MaterialTheme

## 2. 目录结构
```
app/src/main/java/com/nanokvm/app/
  ui/connect/ConnectScreen.kt        连接页(凭据/主题按钮)
  ui/console/ConsoleScreen.kt        主控台(顶栏三 chip/TopBar/ActionBar/视频/覆盖层/统计)
  ui/console/ConsoleToolsSheet.kt    工具箱抽屉(操作|设备管理 分页 + 各对话框入口)
  ui/console/{DeviceSettingsDialog,DevExtrasDialog,VirtualDevDialog,
              TailscaleDialog,UpdateDialog,DeviceMonitorDialog}.kt  设备管理功能
  ui/assistant/{AssistantChatScreen,AssistantChatViewModel,
                LiveDesktopThumb,CuaSessionGate}.kt  智能助手
  ui/terminal/                      终端(WebView+xterm)
  ui/theme/                         One-KVM 配色/主题(含 contentColor 提供)
  data/api/NanoKvmApi.kt            全部设备 REST/WS/上传封装
  data/net/Tls.kt                   trust-all(自签 MVP)
assets/terminal/                    离线 xterm
```

## 3. 构建方法

环境:Windows / JDK 17 / Gradle wrapper 8.11.1 / AGP 8.7.3 / Kotlin 2.0.21 / SDK 35
(依赖 WebRTC `io.github.webrtc-sdk:android:144.7559.14`,aar 大,首拉慢)

```powershell
cd nanokvm-pro-android
.\gradlew.bat :app:assembleDebug        # 调试(多 ABI 输出:app-{abi}-debug.apk)
.\gradlew.bat :app:assembleRelease      # 发布(ABI splits + universal,已签名)
```

- **签名**:`keystore.properties`(本地、gitignore)+ `nanokvm-release.keystore`
  - 内容:storeFile=nanokvm-release.keystore / keyAlias=nanokvm-release / 随机 48 位密码
  - ⚠ 两文件务必异地备份;丢失=已装用户无法覆盖升级
  - gradle 签名配置在 `app/build.gradle.kts` signingConfigs.release(读 keystore.properties)
- **版本号**:改 `app/build.gradle.kts` 的 `versionCode`/`versionName`
- **ABI splits**:`splits { abi { include(armeabi-v7a, arm64-v8a, x86, x86_64); isUniversalApk=true } }`
  - 产物 `app/{abi}-release.apk` + `app-universal-release.apk`;改名格式 `com.nanokvm.app-<版本>-<abi>.apk`
- **Proguard(不可改动三条)**:
  ```
  -dontobfuscate
  -dontoptimize
  -dontshrink
  ```
  注释:任何 shrink 开启动会在 arm64 真机复现 WebRTC 加载 SIGTRAP(真机 Android16 实测;模拟器 x86_64 不触发,勿以模拟器判断)
- **打包解压原生库**:`packaging { jniLibs { useLegacyPackaging = true } }`(保留;非崩溃主因但稳妥)

## 4. 发布方法(GitHub)

```powershell
# 前置:gh auth login(qingkong9579);本地 commit 用 -c commit.gpgsign=false(全局开了 gpg 会卡)
.\gradlew.bat :app:assembleRelease
cd app\build\outputs\apk\release
# 复制改名为 com.nanokvm.app-<ver>-{arm64-v8a,armeabi-v7a,x86,x86_64,universal}.apk
gh release create v0.1.2 --title "..." --notes "..."
gh release upload v0.1.2 com.nanokvm.app-0.1.2-*.apk --clobber
git add -A; git -c commit.gpgsign=false commit -m "release: vX"; git push
```
注意:github.com:443 偶发超时(api 正常)→ 重试即可;gh 上传成功是**静默退出码 0**,勿因无输出重复。

## 5. 测试方法

### 5.1 模拟器(AVD)
- AVD:NanoKVMPro(手机,pixel 配置)/ NanoTablet(平板,已调 1920×1280@240;双 AVD 同开注意宿主资源)
- 启动:emulator.exe -avd X -no-snapshot -gpu host -no-boot-anim -no-audio
  (swiftshader 本机不稳;NanoTablet 高分辨率曾宿主崩溃,现配置已降)
- 驱动手段(全部走 adb):
  - 截图证据:`adb exec-out screencap -p > x.png`
  - 定位坐标:`adb shell uiautomator dump /sdcard/ui.xml`,解析 `bounds="[x1,y1][x2,y2]"`(文本含括号等特殊字符用 grep -F)
  - 输入:`adb shell input tap X Y` / `input text`(%s=空格;`;`、`&` 等会吞字,单段输入)/ keyevent 4 收键盘
  - 清空输入框:Ctrl+A(`input keycombination 113 29`)+ `keyevent 67`
  - 主题:深 `adb shell cmd uimode night yes`,浅 no;也可点 App 内按钮(持久化)
  - 横竖屏:phone `settings put system user_rotation 1/0`(0=竖,1=横,需 accelerometer_rotation 0);平板以此测双栏/堆叠
- 典型链路:安装 → 启动 → 连接页(默认凭据 192.168.5.47/admin/admin,个别环境需手输)→ 主控台
- 验证清单:
  - 直连 4K 出画面、chip 显示 画面/会话/鼠标
  - 设置面板切 WebRTC(模拟器可验;真机 WebRTC 见 5.2)
  - 工具箱两页切换、设备管理各对话框读真值
  - 设备监控曲线滚动、间隔切换 toast
  - 深浅主题各页截图对比(白/黑图标)

### 5.2 真机(无线调试,小米示例)
```powershell
adb pair 192.168.5.38:<配对端口>   # 输入 6 位码
adb connect 192.168.5.38:46831
adb install -r <arm64-v8a release apk>
# 验证 WebRTC(必做,模拟器无法替代):
#   连接 → 设置 → WebRTC → 应用并重连 → 等 10s
adb shell pidof com.nanokvm.app      # 进程存活=通过
adb logcat -d -b crash               # 无 Fatal signal=通过
# H.264 与 H.265 都要各测一次;崩溃会显示 JNI_OnLoad SIGTRAP(配置错误)
```

### 5.3 智能助手会话接管(免重启设备)
- 场景:设备端 cua 服务活着但会话被占/孤儿(服务进程在、token 无人持)→ App 内点刷新
- 预期:logcat `CuaGate: session released & new cua started, claimed token` → 在线恢复(12~25s)
- 原理:客户端走 /api/vm/terminal pty 发 pkill → assistantStart → gate 内 GET 领 token(防竞态)
- 注意:接管窗口 10s 设计防双端互踢;服务端 unpatched(未改设备文件)

### 5.4 常用真值核对(不经 UI)
```bash
TOKEN=$(curl -sk https://<host>/api/auth/login -d '{"username":"admin","password":"admin"}' ... | jq -r .data.token)
curl -sk -H "Authorization: Bearer $TOKEN" https://<host>/api/vm/mdns      # 等
curl -sk -H "Authorization: Bearer $TOKEN" https://<host>/api/storage/image
curl -sk -H "Authorization: Bearer $TOKEN" https://<host>/api/extensions/tailscale/status
# 注意 Tailscale 等在 /api/extensions/ 前缀(易漏 404)
```

## 6. 已知坑速查
| 坑 | 现象 | 解法 |
|---|---|---|
| Row weight 后兄弟不渲染 | 双栏空白/顶栏图标消失 | weight 容器放前面,后随无 weight 项 |
| R8 shrink + WebRTC | arm64 真机 JNI_OnLoad SIGTRAP | -dontshrink(-dontobfuscate -dontoptimize) |
| pty 取数竞态 | 解析空/偶发缺采样 | 见标记后 sleep 600ms 再关 |
| Cursor 未 moveToFirst | 上传读名抛 CursorIndexOutOfBounds | moveToFirst |
| LocalContentColor 恒黑 | 深色图标不可见 | Theme 层 provides onBackground |
| keytool 不在 PATH | 找不到命令 | 用 JDK bin 全路径或 Android Studio jbr |
| gh 上传"无输出" | 误判失败反复传 | 看退出码(--clobber 幂等) |
| github 443 偶发超时 | push/upload 失败 | 重试;api.github.com 正常不代表 github.com |
| adb wireless 直连失败 | connect refused | 先 pair 配对端口+码,再 connect 调试端口 |
| gradle kts 中 java.util 报错 | DSL `java` 扩展遮蔽包名 | 顶部 import java.util.Properties,用裸类名 |
| 输入框预填默认值 | 拼接 adminadmin | Ctrl+A 删除后重输 |

## 7. 交接/参考
- 设备端固件源码/协议对照:../NanoKVM-Pro(server router/service 为本 App 的 API 依据)
- 助手单用户 token 与 cua:见 HANDOFF.md(仓库外上层目录)相关段落

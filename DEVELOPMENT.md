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
- 磨砂玻璃体系 v0.1.4(haze 1.2.2,MIT):控制台改沉浸式布局(视频全屏铺满,顶栏/工具条/键盘/性能 dock/设置面板以磨砂玻璃悬浮于视频上,实时透出画面);设置 Sheet 新增「磨砂玻璃」模糊度(4–40dp)/透明度(5–80%)滑杆,实时预览、DataStore 持久化;工具箱二级参数行优化(ToolParamRow:图标+标题+右侧当前值+内嵌分段)
- v0.1.5:按 design-v2(设计文件.sketch)对齐 UI——登录页居中品牌卡+已保存芯片+外置标签;控制台 S03 三段结构(玻璃 chrome/画面/底部面板),磨砂胶囊操作栏(独占面板,激活胶囊青色高亮,横向可滚动);**模拟触控板面板**(触控区相对移动+双指滚动+物理左/右键);键盘/触控板 navigationBarsPadding 避开系统手势条;工具箱终端/智能助手入口卡+设备管理健康组;S13 确认壳层(参数摘要+警告+红色执行)
- v0.1.6 重要修复:①**触控板在绝对鼠标模式下无响应**——HidHost.mouseRelativeMove 的 `if (!isRelative) return` UX gate 把触控板移动静默丢弃;新增 touchpadMove/Button/Wheel 强制相对报文通路(设备 HID WS 同时接受绝对 6B/相对 4B,无需切模式)。②**断线重连随机失败**(设备返回 "set rate control failed" code -3)——设备流会话活跃/收尾窗口会拒绝流参数写入;connect() 记录 configuredMode,模式未变跳过 configureStream,参数写入容忍失败,applyStreamParams 加单次退避重试。排查手段:trust-all 前提下用 python MITM(CONNECT 隧道)抓 OkHttp 明文请求对比裸调
- 电源状态灯(对齐 web 电源按钮):`GET /api/vm/gpio` 的 `data.pwr`(true=开机绿 false=关机红)为唯一信号源;控制台工具栏下方悬浮磨砂圆角徽标(建流后每 5s 轮询),工具箱电源区置顶且区头挂同一状态灯,电源操作后延迟 2.5s 刷新。web 源码参照 NanoKVM-Pro/web/src/pages/desktop/menu/power/index.tsx(getGpio 5s 轮询,text-green-600)
- 曾试验 AndroidLiquidGlassView(AGSL 折射透镜)并 vendor 其源码,因「透镜必然位于被采样 content 内」造成 RenderNode 自引用递归(RednerThread 栈溢出,需录制期全局抑制+去硬件层才可稳定),且多透镜经共享树仍会互指——已整体移除,统一用 haze 方案
- 视觉打磨(v0.1.3,按 `.workbench/spec.md` 令牌):全局排版刻度(标题15sp/500·正文13sp·次要12sp)、性能/监控曲线渐变填充+实时端点+圆角线帽、工具箱行图标底座(32dp/8dp 圆角)、虚拟键盘等宽键块、图标统一 20dp/40dp 命中区、连接页点阵满屏+卡片描边阴影

### 重要修复记录
- WebRTC 真机(arm64)闪退:**R8 shrink 导致 libjingle JNI_OnLoad SIGTRAP** → proguard 固定 `-dontobfuscate -dontoptimize -dontshrink`(详见 §3)
- Compose 布局坑:Row 内 **weight 之后的行内兄弟不渲染**(双栏空白、顶栏图标消失同源)→ 用「weight 容器在前 + 无 weight 兄弟殿后」规避
- pty 回显竞态:终端 WS 取数需等结束标记后 600ms 再收尾(否则解析空)
- 上传 Cursor 未 moveToFirst → CursorIndexOutOfBounds(两处同源已修)
- 深浅色图标黑对黑:根因无 Surface 时 LocalContentColor 恒黑 → Theme 层按 scheme 提供
- DotGridBackground 曾硬编码浅色 → 改随 MaterialTheme
- 控制台深色状态栏露白:根 Column 只 statusBarsPadding 未铺底色 → background 必须在 statusBarsPadding 之前
- 虚拟键盘 F10-12/CLR 标签被裁:等宽键帽内 padding 挤压文字 → 长标签(>2字符)降 12sp + padding 2dp
- 触控鼠标手势 ANR:pointerInput 协程被取消时 catch(Throwable) 吞掉 CancellationException → while(true) 非挂起自旋;取消异常必须上抛
- 设置面板要能实时预览磨砂效果:ModalBottomSheet 是独立窗口,haze 无法跨窗采样 → 改为树内磨砂面板(scrim + GlassPanel)

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

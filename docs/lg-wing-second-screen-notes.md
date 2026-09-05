# LG Wing 副屏机制研究笔记

> 设备：LM-F100N（韩版 LG Wing），Android 13 (SDK 33)，build `TKQ1.220829.002/7250130230901`。
> 以下全部结论来自真机实测 + 官方 ROM 组件反编译（jadx），供双屏应用开发参考。

## 1. 显示拓扑

| | 主屏 | 副屏（旋盖下） |
|---|---|---|
| 逻辑 displayId | 0 | **4**（LG 全家硬编码） |
| SurfaceFlinger 物理屏 ID | 4630946736071014273 | 4630946299134858882 |
| 分辨率 | 1080×2460，60Hz | 1080×1240（内容区 1080×1114），60Hz |
| 触摸 | 内置 | 内置独立触摸（`subtouch` HAL） |
| flags | 默认主屏 | `FLAG_OWN_DISPLAY_GROUP` + `FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` |

副屏是**带完整系统装饰的独立 display group**：拥有专用 SystemUI
（`com.android.secondary.systemui`）和专用启动器（`com.lge.secondlauncher`，
Launcher3 魔改，以 `type=home` task 常驻 display 4）。副屏还有独立的
Secondary Display Power Controller。

## 2. 第三方 app 上小屏的唯一正道：公开 API

```java
ActivityOptions opts = ActivityOptions.makeBasic();
opts.setLaunchDisplayId(4);           // 副屏
startActivity(intent, opts.toBundle());
```

LG 自家启动器内部同样是 `DisplayManager.getDisplay(4)` + `setLaunchDisplayId`，
没有任何私有魔法。已知坑：

- `Display.getType()` / `TYPE_BUILT_IN` / `TYPE_OVERLAY` 是隐藏 API，
  判断内建屏请用公开 flag：排除 `FLAG_PRIVATE` / `FLAG_PRESENTATION`。
- 从小屏抽屉启动时 Activity 会先落在 display 4，需要自行重定向到目标屏
  （本仓库 `GameActivity.redirectToBiggestInternalIfNeeded()` 的做法）。

## 3. 小屏应用白名单（ATMS 层强制校验）

反编译 `services.jar` 中的 `com.android.server.wm.ATMSMultiDisplayEx`：

```
canBeLaunchedOnSubDisplay(displayId=4):
  1. WING_WHITE_LIST_USER  ← /data/system/users/0/wing_canbe_launched_user_list.xml
  2. WING_WHITE_LIST_DEFAULT ← /system/product/etc/wing_canbe_launched_default_list.xml
                              （韩版可能被 /mnt/product/srtc/srtc/secondscreen/ 同名文件覆盖）
  3. 系统应用且无 LAUNCHER 入口 → 放行
  4. 都不是 → 拒绝启动
  ※ 两个白名单都为空时 forceEnable 放行一切；LG 预置了默认列表所以实际生效
```

- 用户白名单的唯一官方写入途径：**设置 → 第二屏幕 → 应用程序**
  （`com.android.settings/.Settings$SubScreenAppSettingsActivity`，
  action `com.lge.settings.SUB_SCREEN_APP_SETTINGS`）。
  打开开关即调用 `updateLaunchedOnSubDisplayPackageByUser(pkg, true)` 并落盘。
- `ApplicationInfo.restrictMultiDs`（LG 私有补丁字段）是**反向**位掩码：
  bit(1<<displayId) 置位 = 禁止上该屏，bit 32 = 禁止 LinQ 场景。没有"正向声明"。
- 白名单变更不会通知启动器，second launcher 需要重启才会刷新抽屉列表。

## 4. 大屏旋盖模式（Swivel Home）

旋盖展开后主屏运行 `com.lge.launcher3` 的 `LauncherExtension`（Swivel Home）：

- 首屏轮播 = **用户拖拽编辑的快捷卡位**，持久化在启动器私有 DB
  （`SwivelAllAppsInfos.db`），支持 `onItemInsert/Move/Dismiss`。
  添加方式只有长按抽屉图标拖入；LGHome 里另有 `wing/AppLoader`
  （HashSet 前 15 个 launcher app）但不是轮播的数据源。
- 上滑抽屉显示**全部**应用，无小屏那样的白名单过滤。

## 5. LG 私有 API 地图（framework.jar，@hide，反射有 hiddenapi 风险）

- `com.lge.display.DisplayManagerHelper` — `getMultiDisplayId()`
  （multi_display_type=swivel 的设备固定返回 4）、`getSwivelState()`、
  `registerSwivelStateCallback()`、`setWideScreenMode()`
- `com.lge.display.DisplayManagerEx` → `IDisplayManagerEx` —
  `requestForceMirrorMode(displayId)`（小屏镜像主屏）、
  `get/setSubDisplayPowerState()`、`setSubDisplayBrightness()`、
  `getCoverDisplayState()`、`getWideScreenMode/setWideScreenMode()`
- `com.lge.systemservice.core.PostureManager`（服务名 `postureservice`）— 旋盖角度状态
- HAL：`vendor.lge.hardware.dualscreen`（LPWG/副屏信息）、`subtouch`、`coverdisplay`
- 旋盖状态的公开替代方案：vendor 传感器 `LGE Hall Distance Sensor`
  （type 499898131），标准 `SensorManager` 可读

### 5.1 官方"SDK"重建：android.app.ActivityManagerEx 完整 API

**结论先行：LG 从未公开发布过 Dual Screen SDK。** 考古结论：
- Maven Central 上 LG 只发过 `com.lge.developer:qcircle-design-template` 和
  `qpair-apis`（G3 时代），无任何 dualscreen 构件
- Wayback 中 developer.lge.com 只有 QRemote/QSlide/QPair 旧 SDK 文档（2014-2016），
  无 Dual Screen SDK 页面；LG 2021 年退出手机业务后开发者门户关闭
- LG 自家应用（时钟/设置/视频播放器）**编译期直接链接 framework.jar 里的 LG 扩展类**，
  并用 `lgapi.exception.reason=app_internal_use` 元数据标记私有 API 使用许可

这套"SDK"随每台设备 framework.jar 分发（已从设备提取并完整反编译，见
`F:\Android\decompiled\sdk\`）。核心入口 `android.app.ActivityManagerEx`
（`getSystemService("activity")` 返回；binder 与标准 AMS 多路复用，descriptor 嗅探）：

```
// 准入
boolean canBeLaunchedOnSubDisplay(int displayId, String packageName)
boolean updateLaunchedOnSubDisplayPackageByUser(String pkg, boolean enable)  // ⚠ 无权限校验
List<String> getDefaultListOnSubDisplay()

// 跨屏搬运
boolean moveTaskToDisplayAsDisplayId(int taskId, int targetDisplayId, int currentDisplayId)
boolean moveToDisplayAsDisplayId(int mode, int displayId)   // MOVE_TO_DISPLAY=0, TAKE_FROM_DISPLAY=1
boolean moveToDisplayEx(int mode)                            // 含 MOVE_SWAP(两屏互换)
void startSecondHomeActivityAsDisplayId(int displayId)       // 拉起副屏桌面(CoverHome)

// 宽屏模式
void setWideScreenMode(boolean) / boolean getWideScreenMode()
boolean isSupportWideScreenMode(String pkg) / void updateWideModeAppByUser(String, boolean)

// 事件回调
void registerLGActivityTrigger(ILGActivityTrigger)           // 白名单变更推送(7=加,8=删)
```

- 服务端：`ActivityTaskManagerServiceEx`/`ActivityManagerServiceEx`（LG 继承并替换系统
  ATMS/AMS），binder 在标准 `activity` 服务上多路复用（descriptor 嗅探路由）。
- transaction 编号已完整记录（27=canBeLaunchedOnSubDisplay、28=updateLaunched...、
  30=moveTaskToDisplayAsDisplayId 等），理论上 `service call activity <n>` 可直达
  （参数需精确打包；实测参数序列化不匹配会返回异常包）。
- 白名单变更**不是广播**，是 binder 回调（`LGActivityTrigger.activityChanged`），
  启动器注册后实时增删抽屉图标。
- hiddenapi 风险：ActivityManagerEx 不在公共 SDK，反射调用受 non-SDK 限制约束；
  规避需 raw binder（ServiceManager + 手写 parcel）。

## 6. 调试小抄

- 这台机器 `screencap -d` 需要 SurfaceFlinger 物理屏 ID（见 `dumpsys SurfaceFlinger --display-id`），
  不是逻辑 displayId
- PowerShell `>` 重定向会损坏二进制流，截图用 `screencap → /data/local/tmp → adb pull` 中转
- 锁屏状态下副屏活动 `isSleeping=true`，截屏前先 `KEYCODE_WAKEUP`
- 相关设置项：`always_app_on_cover_display`（system）、
  `screen_brightness_for_coverdisplay`（secure）、
  `dont_show_again_second_screen_app_dialog`（LG 副屏兼容性提示）

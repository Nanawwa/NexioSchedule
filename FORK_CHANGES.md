# 这个 fork 改了什么

基于 [HaoZai000/NexioSchedule](https://github.com/HaoZai000/NexioSchedule)。

本 fork 保持与上游同步，只增加下面列出的功能，不改上游已有行为。
所有新增代码同样以 AGPL-3.0 发布。

---

## 1. 课间倒计时（超级岛）

**问题**：超级岛原来只有课前「距上课」和课中「距下课」两种倒计时。下课之后到下一节上课之间是空的，课间短的时候用户得自己看时间。

**做法**：下课时把岛换成课间倒计时，显示下一节课、节次和「距上课」倒计时。到上课时刻由精确闹钟收起，让位给课前提醒。

**实现位置**

| 文件 | 作用 |
| --- | --- |
| `core/.../data/BreakCountdown.kt` | 课间判定的纯计算（新增） |
| `core/.../commonTest/.../BreakCountdownTest.kt` | 15 个用例（新增） |
| `androidApp/.../reminder/IslandBreakReceiver.kt` | 下课瞬间挂岛的广播接收器（新增） |
| `androidApp/.../reminder/IslandNotificationHelper.kt` | 课间岛的参数组装与发送 |
| `androidApp/.../reminder/CourseReminderHelper.kt` | 对账接管、闹钟排程 |
| `ui-shared/.../CourseReminderScreen.kt` | 设置页开关 |

**边界处理**

- 连堂课（前一节下课 = 后一节上课）不显示课间
- 时间重叠的课按开始时间配对，重叠段不算课间
- 自定义时间优先于节次时间
- 课前提醒的提前量已盖住整个课间时，课间岛不发（避免两条倒计时互相覆盖）
- 当天最后一节下课之后不算课间

**开关**：设置 → 课程提醒 → 课间倒计时（默认关）。

**来源**：本 fork 原创。

---

## 2. 临时调课

**问题**：上游的「调课」是拖拽后按周改写课表，多周课会被拆开，改完以后每周都是新样子。但老师临时换教室、今天这节课停了，用户只想改今天，不想在课表里留下永久改动。

**做法**：按日期存一份增删改，叠加在当天课表的解析结果上。课表原始数据不动，日期过去后自动失效。

**能做什么**

| 操作 | 效果 |
| --- | --- |
| 本次停上 | 这一天不显示这节课，也不再提醒 |
| 改教室 | 只影响这一天 |
| 改节次 | 只影响这一天，可同时改教室 |

**入口**：课程详情页每一行的「调整」按钮。那一行本来就写了具体日期，点哪天就改哪天。

**实现位置**

| 文件 | 作用 |
| --- | --- |
| `core/.../data/TempAdjustments.kt` | 存储与叠加逻辑（新增） |
| `core/.../commonTest/.../TempAdjustmentsTest.kt` | 13 个用例（新增） |
| `core/.../data/DayScheduleResolver.kt` | 解析最后一步叠加临时改动 |
| `ui-shared/.../screens/CourseDetailScreen.kt` | 调整入口与弹窗 |
| `ui-shared/.../CourseScheduleApp.kt` | 保存后重排提醒、刷新页面 |

**为什么放在解析层**：今日页、小组件、课前提醒、课间倒计时走的都是同一个 `resolveDaySchedule`，改一处全部一致，不会出现「今日页改了但小组件还是旧的」。

**来源**：本 fork 原创。

---

## 3. Wear OS 表盘数据源

**问题**：课表只在手机上看，抬手腕看不到下一节课。

**做法**：新增 `:wearApp` 模块，给 Wear OS 表盘提供一个标准的 Complication 数据源。不做表盘、不做独立 App，装到手表上不占桌面图标。

**显示内容**

- 正在上课：课名 + 距下课倒计时，长文本带地点、教师、节次
- 课间：下一节课名 + 距上课倒计时
- 今天没课 / 还没同步：对应提示文案

**怎么做到「适配各表盘」**

不自己做表盘，而是给系统提供一个标准数据源。三星自家表盘、WFF 表盘、第三方表盘，只要支持 Complication 就能挂这一项。

**倒计时为什么能走秒**

Complication 的主动刷新被系统限制在平均 5 分钟一次，自己刷新做不出秒级倒计时。这里用 `TimeDifferenceComplicationText` 指向下课/上课时刻，由表盘自己每秒重绘。息屏（AOD）下同样由系统绘制，不需要应用参与。

**数据怎么从手机过来**

走 Google Play services 的 Data Layer：

- 手机推 `DataItem`（不是 `MessageItem`）——持久化、断连自动补发
- 手表收到后落盘本地，表盘随时来取都能同步读到
- 手表也能主动发消息要数据（刚装好、换了一天没收到推送时）

手机端只推「今天还没下课的课」，周次、调休、节假日都在手机端算完，手表不做解析。

**实现位置**

| 文件 | 作用 |
| --- | --- |
| `wearApp/build.gradle.kts` | 模块配置（新增） |
| `wearApp/.../WatchState.kt` | 状态判定：上课中 / 课间 / 没课（新增） |
| `wearApp/.../CourseComplicationService.kt` | 表盘数据源（新增） |
| `wearApp/.../ScheduleSyncService.kt` | 接收手机推送（新增） |
| `wearApp/.../SnapshotStore.kt` | 本地缓存与主动请求（新增） |
| `wearApp/.../ComplicationUpdater.kt` | 状态切换时触发刷新（新增） |
| `androidApp/.../wearable/WearScheduleSync.kt` | 手机端推送（新增） |
| `androidApp/.../wearable/WearRequestService.kt` | 响应手表请求（新增） |

**前提**

- 手表是 Wear OS（Galaxy Watch 4 及以后）
- 手表上有 Google Play services（Data Layer 依赖它）
- 手机与手表同包名

**来源**：本 fork 原创。

---

## 4. 与原版共存

改成独立 `applicationId`，可以与原版同时安装、各自独立运行。

- 默认值仍是 `com.haooz.chedule`（与原版相同）
- 通过 `-PnexioAppId=com.haooz.chedule.fork` 构建共存版
- 应用名显示为「R-Nexio课程表」

顺带修了一处会让共存版失效的问题：`TodayCoursesProvider` 的 authority 原来写死成 `com.haooz.chedule.courses`，而 manifest 里是 `${applicationId}.courses`。改包名后两者不一致，provider 会直接失效。现在从 `context.packageName` 推导，两边始终一致。

**来源**：本 fork 原创。

---

## 构建

本地：

```bash
# 共存版
./gradlew :androidApp:assembleDebug -PnexioAppId=com.haooz.chedule.fork
./gradlew :wearApp:assembleDebug   -PnexioAppId=com.haooz.chedule.fork

# 与原版同包名（覆盖安装原版）
./gradlew :androidApp:assembleDebug
```

CI：推送到 `fork/**` 分支自动构建，产物在上传的 artifact 里。
打 `v*-fork.*` 的 tag 会建 Release。

## 安装到手表

手表开启开发者模式和无线调试后：

```bash
adb connect <手表IP>:<端口>
adb install wearApp/build/outputs/apk/debug/wearApp-debug.apk
```

然后在表盘上长按 → 选择「小组件 / Complication」→ 找到「课程表」。

## 上游同步

```bash
git fetch origin
git rebase origin/master
```

新增功能都在独立文件里，对上游文件的改动集中在少数几处接入点，
冲突范围可控。README 与 CHANGELOG 上游有更新时，以上游为准。

---

## 许可

AGPL-3.0，与原项目一致。分发本 fork 的修改版本时，同样需要提供完整源码。

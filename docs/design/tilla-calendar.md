# Tilla 日历（Calendar）功能逆向设计规格

> 目标：为 KMP 版 RecurringExpenseTracker（`de.dbauer.expensetracker.shared`）复刻 Tilla 的日历页。
> 结论先行：**Tilla 确实有日历**，名为 Calendar / 日历，入口在**订阅列表页右上角的「⋯」更多菜单**里（不是底部 tab，也没有底部导航栏）；Play 商店的 12 张截图**完全没有展示它**，所以只能从 APK 反推。本文所有数值都来自 APK 2.8（versionCode 344）。

## 0. 一句话结论

Tilla 的日历 = 「一张卡片里的月历 + 下方当日订阅清单」：卡片内是 16dp 内边距的月标题行（`October 2026` + 左右圆形箭头）、单行星期头、7 列 1:1 日格（格内是圆形选中块）；**某天有订阅时该格用「订阅图标（24dp）」替代日期数字**，同一天多条时右上角挂 `+N` 数目徽标；点某天 → 选中态切到该格，卡片下方列出当天所有订阅（Tilla 彩色卡），点卡片进详情页。整页纵向可滚动，顶部是固定 `TopAppBar("Calendar")`，**没有 FAB、没有今日按钮**，换月靠箭头或左右滑动。

---

## 1. 证据清单

| 证据 | 路径 | 用途 |
| --- | --- | --- |
| Play 商店页（已存 HTML） | `.toolchain/tmp/tilla_play.html` | 描述文本、版本 2.8.1（2026-09-29 更新）、13 张图 URL；确认描述里**没有** calendar 字样 |
| 12 张商店图 | `.toolchain/tmp/tilla/shot1..12.png` | shot1/2/4 主列表；shot3 是 M3 `DatePickerDialog`（编辑订阅的 Billing date）；shot5 分析页；shot6/7/12 备份；shot8/9/10/11 是平板版 + 旧 M2 日期选择器。**无日历页截图** |
| APK | `.research/tilla/tilla-2.8.xapk`（19.9 MB，APKPure XAPK；base `com.pavelrekun.tilla.apk` 19.2 MB + `config.arm64_v8a` + `config.mdpi`） | 全部结论来源 |
| 反编译 | `.research/tilla/apktool_out/`（apktool 3.0.3）、`.research/tilla/jadx_out/sources/`（jadx 1.5.6） | 资源 + 代码 |
| 商店文案 | `.research/tilla/faq.md`（pavlorekun.dev/projects/tilla/documents/faq.md） | 功能边界：无日历相关说明 |

APK 事实：`versionName 2.8 / versionCode 344 / minSdk 32 / targetSdk 37`；单 Activity `com.pavelrekun.tilla.navigation.NavigationActivity`（`Theme.Tilla`）；**Jetpack Compose + Material3**（`androidx.compose.material3`、无 Flutter/RN 资产）；代码经 R8 混淆（`defpackage.a/b/c…`），但 Compose 参数、`j$.time`、字符串资源完好；`com.pairip.application.Application` + `libpairipcore.so` = PairIP 加固（只影响壳，dex 可读）。

---

## 2. 导航与信息架构

Tilla **没有底部导航栏**。主页（`SubscriptionsRoute` = 订阅列表）顶栏三段式，与 shot1 一致：

```
[≡ filter]        All subscriptions ⌄            [⋯]   ← 右上角溢出菜单
                                                     ├─ Calendar         ic_navigation_calendar
                                                     ├─ Analytics        ic_navigation_analytics
                                                     ├─ Archive          ic_navigation_archive
                                                     ├─ Check logs       （仅 debug 构建）
                                                     └─ Settings   PRO   ic_navigation_settings + "PRO" 角标
```

- 菜单项模型 `kh3`（toString=`MoreMenuItem(icon, title, onClick, isSaleEnabled)`），由 `fl5.b` 按上述顺序构建，`DropdownMenu` 展开；`isSaleEnabled=true` 的条目（Settings）右侧画一个 20dp 高圆角小徽标。
- 路由（`fv5` NavHost）：`OnboardRoute`（启动目的地）→ `SubscriptionsRoute`；`CalendarNavigationRoute` / `AnalyticsNavigationRoute` / `ArchiveNavigationRoute` / `PaymentsNavigationRoute`（Analytics 子页）/ `DetailsRoute`（`tilla://details` deeplink）/ `ModifySubscriptionNavigation` / `BundleRoute` / `SettingsRoute` 及设置子页 / `AboutRoute` / `OtherAppsRoute`。
- 日历的注册（`fv5` 第 104 行）明确带一个回调：`navigateToDetails(NavController, Subscription)` → **日历里点订阅卡会进详情页**。
- 日历页顶栏：`jz0.c(title = stringResource(R.string.navigation_destination_calendar), …)`，navigationIcon/actions 传 null → 用默认返回箭头，标题“Calendar”，无 action。
- 日历图标 = Material `calendar_month` 风格（`res/drawable/ic_navigation_calendar.xml`，24dp，path 里带 9 个日期小方块），`android:tint="?colorControlNormal"`。

---

## 3. 日历屏幕规格

### 3.1 布局（自上而下，单一纵向滚动）

```
┌──────────────────────────────────────────────┐
│ ←  Calendar                                  │  M3 顶栏：默认返回箭头 + 标题；无 action
├──────────────────────────────────────────────┤
│ 16dp                                          │
│ ┌──────────────────────────────────────────┐ │  ← Surface/Card（月卡，padding 16dp）
│ │ October 2026                 (‹)  (›)    │ │  18sp / W500 ；箭头 32dp 圆形，底 primaryContainer
│ │                                          │ │
│ │            16dp 竖向间隔                  │ │
│ │  S    M    T    W    T    F    S         │ │  onSurfaceVariant / W500 / 居中 / 每列 weight(1f)
│ │                                          │ │  8dp 竖向间隔
│ │  ○    ○    ○    ○    ○    ○    ○         │ │  Column → Row(7)：aspectRatio(1f) + padding(8dp) + clip(Circle)
│ │  ○    ○    ○    ●24  ○    ○    ○         │ │  选中=primary 实心圆；今天=primaryContainer
│ │  ○    ○    ○    ○    ○    ○    ○         │ │  有订阅=24dp 图标；多条=右上 "+N" 徽标
│ │  ○    ○    ○    ○    ○    ○    ○         │ │
│ └──────────────────────────────────────────┘ │
│ 16dp                                          │
│ 24 October 2026                               │  本地化 LONG 日期，22sp / W700，左右 16dp
│                                               │
│ ┌──────────────────────────────────────────┐ │  ← 当天订阅（Tilla 彩色卡），间距 8dp
│ │ ⬤  Spotify                     €5,99     │ │  图标 36dp，标题 20sp，副标题 14sp
│ │    22 DAYS                               │ │
│ └──────────────────────────────────────────┘ │
│  …                                            │
└──────────────────────────────────────────────┘
```

- 根：`Scaffold(topBar = { TopAppBar(title = "Calendar", navigationIcon = 默认返回箭头) })`，**无 bottomBar / 无 FAB / 无 action 按钮**。
- 内容列 `Modifier.verticalScroll(state)`：卡片 → 16dp → 日期标题 → 列表。整页一起滚（月卡不吸顶）。
- 月卡外围 16dp 水平 padding；卡片内部再 16dp padding；即网格可用宽度 = `screenW - 32 - 32`。

### 3.2 尺寸 / 颜色 / 字体表（全部来自 `ln2.*`、`as6.e/f`、`s21.e`、`rt6.c`、`oq`）

| 元素 | 规格 | 证据 |
| --- | --- | --- |
| 月卡 | M3 `Surface`（`h62.b`），圆角用主题 shape，`padding(16.dp)` | `ln2.d` → `h62.b(Modifier.padding(16.dp), …)` |
| 月标题 | 文本 `"$monthName $yearName"`，**18sp**，`FontWeight.Medium(500)`，`Modifier.weight(1f)` 左对齐，`padding(16.dp)` | `rt6.c`：`m24.j(18)` + `f12.u` + `wa3.z(...,16f)` |
| 上/下月按钮 | 32dp 圆（`CircleShape`），背景 `colorScheme.primaryContainer`，图标 24dp chevron（AutoMirrored KeyboardArrowLeft/Right），两按钮间 16dp | `rt6.c`：`h62.q(…, scheme.c, CircleShape)` + `fa5.m(...,32f)` |
| 月标题 → 星期行 | 16dp 竖向间隔 | `ua` case 5: `Spacer(padding vertical 16dp)` |
| 星期行 | 7 个 `Text`，每个 `weight(1f)`，颜色 `colorScheme.onSurfaceVariant`，**W500**，居中，上下 8dp 内边距 | `s21.e`：`sl0.s` + `f12.u` |
| 星期行 → 网格 | 8dp 竖向间隔 | `as6.f(…, padding(vertical = 8.dp), …)` |
| 网格 | `Column(Arrangement.Top)` → 每行 `Row`，**7 列**，列宽 `weight(1f)`；`chunked(7)`；不足 7 个补 `Spacer(weight(1f))` | `as6.f`：`uk0.S(7, list)` + `y80.a(weight)` |
| 日格 | `aspectRatio(1f)` → `padding(8.dp)` → **`graphicsLayer(shape = CircleShape(50%), clip = true)`** → `background(color)`：即 1:1 方格内缩 8dp 的**圆形**色块，可点击 | `as6.e`：`u(m)`=aspectRatio(1f)、`wa3.z(m,8f)`、`uw4.h(m, yk4.a)`=clip(CircleShape)、`h62.q(…, color, cy4.h)`、`wa3.k(m, onClick)` |
| 日格底色 | 选中 = `primary`；今天 = `primaryContainer`；否则 `Color.Unspecified`（透明） | `as6.e`：`scheme.a / scheme.c / cl0.g` |
| 日格前景 | 选中 = `onPrimary`；今天 = `onPrimaryContainer`；当月 = `onSurface`；非当月 = `onSurface.copy(alpha=0.4f)` | `as6.e`：`scheme.b / .d / .q / cl0.b(0.4f, scheme.s)` |
| 日期数字 | 字号不显式覆盖（继承 Text 默认，≈14–16sp）；今天/选中 `Bold(700)`，其余 `Normal(400)`；居中 | `as6.e`：`f12.v / f12.t` |
| 有订阅的日格 | 用 **24dp 订阅图标替换数字**（取 `list.first()`；`Image`，`contentDescription = subscription.title`；`shouldBeTinted` 时用 `ColorFilter.tint(subscription.color)`，选中态强制 `onPrimary` 色调） | `as6.e`：`k73.a(icon, 24dp, colorFilter)` |
| 多条徽标 | 右上角 `12dp` 圆（偏移 x=+8dp, y=-8dp），底 `primary`，文字为条数，**8sp**，色 `onPrimary` | `as6.e`：`g81.m(m, 8f, -8f)` + `h62.q(...,12f,CircleShape)` + `m24.j(8)` |
| 卡片 → 日期标题 | 16dp 间隔；日期标题 `padding(horizontal 16dp, bottom 16dp)`，**22sp / W700**，`DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)` | `ln2.i` |
| 当日列表 | `Column`，`Arrangement.spacedBy(8.dp)`，`padding(horizontal 16dp, vertical 8dp)` | `ln2.i`：`pr(8f, true, …)` |
| 订阅卡 | Tilla 彩色卡：底色 = 订阅色（`suspended/archived` 时 alpha 0.5），圆角主题 shape，图标 36dp，标题 20sp，副标题 14sp（`… 22 DAYS`），右侧金额 | `xo1.q`（`h62.b` + `if6.c(color, alpha)` + `m24.j(20)/j(14)`） |
| 空态插图 | `R.drawable.pic_not_found`（192dp 矢量，88dp 圆底，主色 `@color/colorLogo`），**渲染 140dp** | `wa3.a(mod, title, desc, pic_not_found, 140f)` |

主题色：整个日历只消费 Material3 语义色（`primary / onPrimary / primaryContainer / onPrimaryContainer / onSurface / onSurfaceVariant`）。Tilla 支持「动态取色 + 付费多主题」，所以日历没有硬编码配色；品牌基色是 `@color/colorLogo = #006A68`（深青），通知/插图/空态用它。默认回退是 M3 baseline 方案（`fl0` = light tokens，`#6750A4` 系）。

### 3.3 日格状态优先级

| 状态 | 组合规则 | 底色 | 前景 | 内容 |
| --- | --- | --- | --- | --- |
| 选中 + 有订阅 | selected 优先级最高 | `primary` | `onPrimary` | 图标（tint 成 onPrimary） |
| 选中 + 无订阅 | — | `primary` | `onPrimary` | 数字（Bold） |
| 今天 + 有订阅 | 未选中时 | `primaryContainer` | `onPrimaryContainer` | 图标 |
| 今天 + 无订阅 | — | `primaryContainer` | `onPrimaryContainer` | 数字（Bold） |
| 当月普通 + 有订阅 | — | 透明 | `onSurface` | 图标 |
| 当月普通 + 无订阅 | — | 透明 | `onSurface` | 数字（Normal） |
| 相邻月（当月前后补位） | `isCurrentMonth == false` | 透明 | `onSurface @40%` | 数字（可点，点了会切到该月） |
| 未来/过去 | **无区别**（不置灰、不加标记） | — | — | — |

> 注意：**日历不区分「已付/未付」**。Tilla 的付款状态在 `Payment`/`paymentTracking`/「Payments history」页里，日历日格只有「有没有订阅」二值。

### 3.4 手势与动画

- **左右滑动换月**：月卡上有 `Modifier.pointerInput { detectHorizontalDragGestures }`（`de0`）；拖动位移累计进 `Animatable<Float>`（`tz3`），**松手时 `offset > 100f` → 上一个月，`offset < -100f` → 下一个月**，然后 `snapTo(0f)`。拖动过程中卡片本身**不跟随位移**（代码里只有 offset 判定，没有 `graphicsLayer.translationX`），因此表现为「滑动即翻页」，无跟手动画。
- **点箭头**：与滑动同一对回调（`currentMonth.plusMonths(1)` / `minusMonths(1)`，见 `ge0`），切换后重新挂起加载该月数据。
- **点某天**：`onDateClick(date)` → ViewModel 协程拉取该日订阅（`n0`），`selectedDate` 立即更新（`l0` case 11/12），列表区在数据回来前保持旧内容（有 `isLoading` 字段但 UI 未显示转圈）。
- 换月时 `today` 会被重算（`l0` case 11 传 `localDate2 = today`），`selectedDate` **不重置**（mask 1022），所以换月后下方仍显示原来选中的日期——这是一个可以改进的点。
- 无共享元素转场、无月切换动画、无骨架屏。

### 3.5 空态 / 加载

- 只针对「选中日没有订阅」：插图（140dp）+ 标题 `Nothing planned` + 描述 `There are no subscription payments scheduled on this date`（居中，卡片外、列表位置）。
- 「整月没有订阅」**没有专门的空态**：月历照常渲染，任一天点开都是上面的空态。
- 首帧：`td0` 的 `daysInMonth/dayOfWeekLabels/monthName` 初始为空 → 会先渲染一个空卡片，数据到达后填充（无 skeleton）。

### 3.6 无障碍

- 日格是 `Modifier.clickable` 的 Box；无显式 `contentDescription`/`semantics`（数字文本会被读出；图标态读的是图标的 contentDescription = 订阅名，**丢掉了日期**）。→ 复刻时应补：`contentDescription = "24 October 2026, 2 subscriptions"`、`selected`/`disabled` 语义、`Role.Button`。
- 星期头用 `FULL_STANDALONE` 名称做 contentDescription、`NARROW_STANDALONE` 做显示文本（`ln2.h`/`zd0`）。
- 箭头按钮 32dp < 48dp 最小点击区，命中区靠 M3 IconButton 默认 48dp 撑开。
- 深色模式：全部走语义色，自动适配；空态插图是白色底矢量 + 品牌青，深色下仍可见。

---

## 4. 数据模型推断

### 4.1 日历状态（APK 中的 `td0`，toString 字段名可直接读）

```kotlin
data class State(
    val currentMonth: YearMonth,                       // 展示中的月份
    val selectedDate: LocalDate,                       // 选中日期，默认 today
    val today: LocalDate,
    val daysInMonth: List<CalendarDay>,                // 上一月尾 + 本月 + 下一月头，拼好后 chunked(7)
    val monthDisplayName: String,                      // "October"
    val yearDisplayName: String,                       // "2026"
    val dayOfWeekLabels: List<String>,                 // 7 个，按 locale 首日星期旋转
    val subscriptionsForSelectedDate: List<Subscription>,
    val monthlySubscriptions: Map<LocalDate, List<Subscription>>,  // 本月每天 → 订阅
    val isLoading: Boolean,
)
```

### 4.2 日格模型（`vd0`）

```kotlin
data class CalendarDay(
    val date: LocalDate,
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasSubscriptions: Boolean,
    val subscriptions: List<Subscription>,
)
```

### 4.3 月历引擎（`xd0`/`zd0`，等价 kizitonwose Calendar 的 SimpleCalendar）

- `firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek`（周首日跟随地区）。
- `CalendarMonth(year, month, numberOfDays, firstDayOfWeek, startUtcTimeMillis)`；内部所有日期换算成 **UTC 当天 0 点 epoch millis**（`ZoneId.of("UTC")`）做 key，`today()` 用系统时区取日期后转 UTC 零点。→ 跨时区/夏令时安全，但**不要用 epoch millis 当唯一 key 存库**。
- 星期名：`DayOfWeek.getDisplayName(FULL_STANDALONE / NARROW_STANDALONE, locale)`。
- 月切换：`month.plusMonths(±1)`，`numberOfDays = lengthOfMonth()`（闰年由 `LocalDate` 处理）。

### 4.4 递归规则 → 日期（`o60`，纯函数，可直接照搬）

`BillingCycle(value: Int, type: CycleType, weekOfMonth: Int?, dayOfWeek: DayOfWeek?)`；`CycleType = DAY | WEEK | MONTH | YEAR | WEEKLY_IN_MONTH`（`WEEKLY_IN_MONTH` = 「每月第 N 个星期几」，`weekOfMonth = -1` 表示「最后一个」）。

```text
fun datesInMonth(firstBill: LocalDate, cycle: BillingCycle, year: Int, month: Int, maxDate: LocalDate?): List<LocalDate>
  1) d = firstBill；按 cycle 向过去步进，直到 d <= 当月 1 日（保证起点 <= 月初）
     （WEEKLY_IN_MONTH 用 TemporalAdjusters.dayOfWeekInMonth / lastInMonth 实现）
  2) 从 d 向后步进，收集 d.year==year && d.month==month && (maxDate==null || d <= maxDate)
  3) 直到 d > 当月最后一天
```
- `nextPaymentAfterToday(firstBill, cycle)`：同类步进直到 `d > today`（`o60.a`）。
- 用途：`monthlySubscriptions` 由「每个订阅先算 `getNextPaymentDay()`，再取该月全部发生日」得到；单次支付（`isOneTime`）/ 已取消（`endDate` 之后）不产生日期。
- **Tilla 的日格显示「该订阅本月的所有发生日」，而不是仅 next payment**（否则 MONTH 类型每个月都会有条目，日历才成立）。

### 4.5 边界与地区

| 情况 | Tilla 行为 | 建议（复刻时） |
| --- | --- | --- |
| 周首日 | 跟随 `WeekFields.of(locale)`（zh-CN = 周一，en-US = 周日） | 同左，不要硬编码周日 |
| 月首/月末补位 | 相邻月日期补满整行；非当月 40% 透明；可点击并切月 | 同左 |
| 闰年/2 月 | `lengthOfMonth()` 天然正确 | 同左 |
| 时区 | 所有日期以 UTC 零点 millis 归一，`today` 取系统时区 | 用 `kotlinx.datetime` + `TimeZone.currentSystemDefault()`，比较用 `LocalDate` |
| 一天多条 | 图标取 `list.first()`（列表中第一条），右上角徽标显示总数 | 取「金额最大/最近到期」+ 徽标更可读（assumption） |
| 一天 0 条 | 显示数字 | 同左 |
| 每天都有订阅（DAILY） | 全月格子都是图标，数字完全消失（可用性风险） | 建议改为「数字 + 底部 4dp 圆点」或数字下方的图标条 |
| 整月为空 | 正常日历 + 每次点开空态 | 加整月空态文案更友好 |
| `isOneTime` | 只出现一次 | 同左 |
| 订阅结束（`endDate`） | 之后的月份不出现 | 同左 |

---

## 5. 交互流程

```
订阅列表顶栏 [⋯] → "Calendar"（push 到新路由，顶栏出现 ← 返回）
   ├─ 月历卡
        ├─ 点 ‹ / › 或 左/右滑动  → currentMonth ±1 → 重载 monthlySubscriptions（selectedDate 不变）
        ├─ 点某日格 → selectedDate = 该日 → 重载当天列表 → 该格底色变 primary、数字/图标变 onPrimary
        └─ 点相邻月的补位日 → 同上（并会切到那个月）
   └─ 当日列表
        ├─ 空  → 插图 + "Nothing planned" + 说明
        └─ 点订阅卡 → DetailsRoute（订阅详情：金额、周期、下次付款、付款历史…）→ 返回
```
- 日历页**不能**直接标记已付、不能新增订阅、不能编辑；所有写操作在详情页/付款历史页。
- `Payments history` 是 Analytics 的子页（`m24.navigateToPayments`），与日历无耦合。

---

## 6. KMP `commonMain` 实现计划

沿用目标工程结构：`shared/src/commonMain/kotlin/de/dbauer/expensetracker/shared/{model,ui,viewmodel}`，Compose Multiplatform + Material3 + `kotlinx-datetime`（工程已在用 `kotlin.time.Instant` + `TimeZone.UTC`）。

### 6.1 组件树

```
ui/calendar/CalendarScreen.kt
  CalendarScreen(vm: CalendarViewModel, onExpenseClick: (Int) -> Unit)
  └─ Scaffold(topBar = TopAppBar(R.string.calendar_title))
      └─ Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
          ├─ MonthCard(state)                       // Surface + Column(padding 16.dp)
          │   ├─ MonthHeader(month, year, onPrev, onNext)         // 18sp/500 + 32dp 圆按钮
          │   ├─ Spacer(16.dp)
          │   ├─ WeekdayHeader(labels)                           // weight(1f) x7, W500, onSurfaceVariant
          │   ├─ Spacer(8.dp)
          │   └─ DayGrid(days, onDayClick)                        // Column + Row x N + weight(1f)
          │        └─ DayCell(day)                                // aspectRatio(1f)+padding(8dp)
          ├─ Spacer(16.dp)
          ├─ DayDetailHeader(selectedDate)                        // 22sp/700, localized LONG
          └─ if (items.isEmpty()) EmptyDayState() else
               Column(spacedBy(8.dp)) { ExpenseCard(item, onClick) }  // 复用现有 Tilla 彩卡
```
复用现有：`ui/elements` 的卡片/图标、`ui/theme` 的 `MaterialTheme.colorScheme`、`ExpenseService` 图标与配色、`data/CurrencyValue` 金额格式化。

### 6.2 状态持有者

```kotlin
// viewmodel/CalendarViewModel.kt
class CalendarViewModel(private val repo: ExpenseRepository, private val clock: Clock) : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState.initial(clock.today()))
    val state: StateFlow<CalendarUiState>

    fun onPrevMonth(); fun onNextMonth()          // currentMonth ± 1，重载
    fun onDayClick(date: LocalDate)               // selectedDate = date，重载当天列表
    fun today(): LocalDate
}
```
- `combine(expensesFlow, calendarStateFlow) { … }` 派生 `daysInMonth` 与 `byDay`，避免每次点击都查库。
- `CalendarUiState` 放 `ui/calendar/CalendarUiState.kt`，字段对齐 §4.1（把 `monthDisplayName/yearDisplayName` 换成 `YearMonth` + `Locale`，显示名在 Composable 里算，便于测试）。

### 6.3 纯函数（建议签名，全部无副作用、放 `model/calendar/`）

```kotlin
// model/calendar/CalendarMath.kt
fun monthGrid(
    month: YearMonth, firstDayOfWeek: DayOfWeek, today: LocalDate,
    selected: LocalDate, marks: Map<LocalDate, List<Int>>,
): List<List<CalendarDayCell?>>                    // 行数 = ceil((leading + 当月天数)/7)，7 列，null = 行尾补位

fun leadingBlanks(month: YearMonth, firstDayOfWeek: DayOfWeek): Int
fun weekLabels(firstDayOfWeek: DayOfWeek, weekdays: List<WeekdayNames>): List<String>  // 显示用 NARROW
fun weekLabelsForA11y(...): List<String>                                             // FULL

// model/calendar/Occurrences.kt（对齐 Tilla o60 / 现有 DateTimeCalculator）
fun occurrencesInMonth(
    firstPayment: Instant, everyX: Int, recurrence: DateTimeUnit.DateBased,
    month: YearMonth, endDate: Instant?, timeZone: TimeZone = TimeZone.UTC,
): List<LocalDate>

fun nextPaymentOnOrAfter(
    firstPayment: Instant, everyX: Int, recurrence: DateTimeUnit.DateBased, from: LocalDate,
): LocalDate

// model/calendar/CalendarMarks.kt
fun marksForMonth(
    expenses: List<RecurringExpenseData>, month: YearMonth, overdueBefore: LocalDate,
): Map<LocalDate, List<CalendarItemMark>>          // 含 count / isPaid / isOverdue
```
`CalendarItemMark(expenseId, colorArgb, serviceId, amountText, isPaid)` —— 图标与金额在 UI 层从 `expenseId` 取。

### 6.4 `commonTest` 单测清单（纯逻辑，不依赖 Compose）

1. `leadingBlanks`：2026-10-01 是周四 → 周一首日 = 3，周日首日 = 4；2026-02 为 28 天（2024-02 为 29）；两种 `firstDayOfWeek` 各跑一组。
2. `monthGrid`：行数正确、每行恰 7 格、相邻月补位的 `isCurrentMonth=false`、today/selected 标记唯一。
3. `occurrencesInMonth`：Monthly 每月 31 号在 2 月（跳过/顺延策略需明确定义）、Yearly 2/29 在平年、Weekly 跨月、Daily 7 天内、`endDate` 截止、`everyX=2` 隔月。
4. `nextPaymentOnOrAfter`：边界 `from == 当天`（应返回当天）、`from` 在首次付款之前。
5. `marksForMonth`：同日多条聚合、归档/取消订阅排除、`isPaid` 透传、空月返回空 map。
6. 时区：`TimeZone.of("Asia/Shanghai")` 与 `UTC` 下同一 Instant 得同一 `LocalDate`（回归测试，防「跨年 23:30」bug）。

### 6.5 与现有 Tilla 风格的一致性

- 卡片：继续用 `#` 彩色块 + 16dp 圆角 + 大图标 + 右侧金额（`dist/README.md` 的 92 图标 / 快速模板体系不变）。
- 日格底色 APK 用**圆形**（clip 50%）；与 M3 `DatePicker` 的圆形选中一致，建议保留；若想更「Tilla 色块」可改 12dp 圆角（**assumption**）。
- 「有订阅的格子用图标替换数字」可保留，但建议：图标 20dp + 底部保留 10sp 数字，或数字下方 4dp 圆点，避免整月无数字。

---

## 7. 差距 / 假设（Observed vs Assumed）

| 项 | 状态 |
| --- | --- |
| 日历入口在订阅列表右上「⋯」菜单，非底部 tab | **Observed**（`fl5.b` 构建 `MoreMenuItem` 列表 + `wf.a` DropdownMenu；全工程 `new kh3` 仅此一处） |
| 日历页顶栏标题 "Calendar" + 默认返回箭头 | **Observed**（`jz0.c(title, …, null, null)`） |
| 月卡 16dp 内边距、7 列、`aspectRatio(1f)`、`padding(8dp)`、圆形色块 | **Observed**（`ln2.d`/`as6.e`/`as6.f`/`yk4.a`） |
| 日格：选中=primary、今天=primaryContainer、非当月=onSurface@40% | **Observed**（`as6.e`） |
| 有订阅 → 24dp 图标替换数字；多条 → 12dp 圆形 +N 徽标（8sp） | **Observed**（`as6.e`） |
| 月标题 18sp/W500、箭头 32dp 圆底 primaryContainer | **Observed**（`rt6.c`） |
| 日期标题 22sp/W700 + `FormatStyle.LONG` | **Observed**（`ln2.i`） |
| 空态文案 + 140dp 插图 | **Observed**（`wa3.a` + `pic_not_found`） |
| 滑动阈值 100（px 单位，未见 dp 换算） | **Observed 数值 / 单位存疑** |
| 顶部 AppBar 文案 "Calendar"、无 FAB | **Observed**（`i` case 2 + `jz0.d(...)` 无 FAB 参数） |
| 日历显示「本月所有发生日」而非仅 next payment | **Strong inference**（`o60.b` + `monthlySubscriptions` map） |
| 每格高度 ≈ 42dp（360dp 屏推算），图标 24dp | **Inferred**（无固定高，由 aspectRatio 决定） |
| 数字字号 | **未取到**（未显式设置 fontSize，继承 Text 默认） |
| 星期头文字形态（单字母 / 三字母 / 全称） | **Inferred**：`zd0` 同时生成 `FULL_STANDALONE` 与 `NARROW_STANDALONE`，`ln2.h` 用全称作无障碍；显示取窄体（列宽 ≈42dp 只能容纳窄体） |
| 日格数字与图标的默认字号/尺寸来源 | **Inferred**：数字未设 fontSize（走 `LocalTextStyle`），图标显式 24dp |
| 是否显示「今日」快捷按钮 | **无**（Observed 无对应代码） |
| 相邻月补位日是否可点 | **Inferred**（clickable 未按 `isCurrentMonth` 区分，故可点） |
| 主题具体 hex | 语义色 Observed；具体十六进制随「动态取色/付费主题」变化，未固定 |
| Tilla 是否有「标记已付」 | 有 `Payment`/`paymentTracking`/付款历史页，但**日历页无**（Observed） |

---

## 8. 附录：精确资源 / 类名 / 提取方式

**字符串（`res/values/strings.xml`）**

| key | EN | zh-CN | zh-TW |
| --- | --- | --- | --- |
| `navigation_destination_calendar` | Calendar | 日历 | 行事曆 |
| `calendar_empty_title` | Nothing planned | 暂无计划 | 沒有計劃 |
| `calendar_empty_description` | There are no subscription payments scheduled on this date | 该日期没有计划好的订阅付款 | 此日期沒有安排訂閱付款 |
| `settings_card_layout_date_pattern_relative_calendar` | Calendar (year-month-day) | 日历（年-月-日） | 日曆（年-月-日） |

**资源 id**：`navigation_destination_calendar 0x7f1201f8`、`calendar_empty_title 0x7f12006b`、`calendar_empty_description 0x7f12006a`、`ic_navigation_calendar 0x7f08011b`、`pic_not_found`（drawable，192dp 矢量，填充 `@color/colorLogo #006A68` + `#F0F7FD/#FCFCFC/#FFFFFF`）、`colorLogo #006a68`。

**类名（APK 混淆后 → 语义）**

| 混淆名 | 语义 |
| --- | --- |
| `com.pavlorekun.tilla.feature.calendar.CalendarNavigationRoute` | 日历路由（@Serializable object） |
| `he0` | `CalendarViewModel`（`qk5` repo + `o60` 日期引擎，`WeekFields.of(locale)`） |
| `td0` | `CalendarUiState`（§4.1） |
| `vd0` | `CalendarDay`（§4.2） |
| `xd0` / `zd0` / `c03` | 月历引擎接口 / `SimpleCalendar`（API≥26）/ 旧 API 实现 |
| `ae0` / `ud0` | `CalendarMonth(year, month, numberOfDays, firstDayOfWeek, startUtcTimeMillis)` / `CalendarDate(year, month, dayOfMonth, utcTimeMillis)` |
| `o60` | 递归日期计算（`occurrencesInMonth` / `nextPayment`） |
| `ln2.g` / `ln2.f` / `ln2.d` / `ln2.i` | 界面根（ViewModel 注入）→ Scaffold → 月卡 → 当日详情 |
| `oq` | 可滚动内容列（月卡 + 详情 + 滑动换月手势） |
| `ua` case 5 | 月卡内容（标题 + 星期行 + 网格） |
| `as6.e` / `as6.f` | 日格 / 网格（chunked(7)） |
| `s21.e` / `rt6.c` | 星期行 / 月标题行 |
| `xo1.q` | 当日订阅卡（Tilla 彩卡） |
| `de0` + `ja` case 6 | 左右滑动换月（阈值 ±100f） |
| `fl5.b` / `kh3` | 右上「⋯」更多菜单的项列表（`MoreMenuItem(icon,title,onClick,isSaleEnabled)`） |
| `fv5` | NavHost（日历注册 + `navigateToDetails`） |
| `jz0.c` | 全 App 通用 `TopAppBar(title, scrollBehavior, navigationIcon, actions)` |

**提取命令（可复现）**

```bash
cd /home/Spark/Projects/GitHub_APPS/.research/tilla
curl -sSL -A "<browser UA>" "https://d.apkpure.com/b/XAPK/com.pavelrekun.tilla?version=latest" -o tilla-2.8.xapk
unzip -o tilla-2.8.xapk com.pavelrekun.tilla.apk && unzip -o com.pavelrekun.tilla.apk -d apk
java -jar tools/apktool.jar d -f -p framework -o apktool_out com.pavelrekun.tilla.apk   # 注意 -p 本地 framework 目录（HOME 只读）
XDG_CONFIG_HOME=$PWD/xdg/config XDG_CACHE_HOME=$PWD/xdg/cache tools/jadx/bin/jadx -j 4 -d jadx_out --no-res com.pavelrekun.tilla.apk
grep -rl "0x7f12006b" apktool_out/smali*/   # 以字符串资源 id 反查混淆类
grep -rn "new td0(\|td0.a(" jadx_out/sources # 反查状态与更新点
```
未提交任何 APK/反编译产物；`.research/tilla/` 仅本地留存（`tilla-2.8.xapk`、`com.pavelrekun.tilla.apk`、`apktool_out/`、`jadx_out/`、`tools/`，约 309 MB）。

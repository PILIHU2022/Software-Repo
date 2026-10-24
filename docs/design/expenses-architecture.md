# RecurringExpenseTracker 架构图谱（供 Tilla 风格日历页接入）

仓库：`/home/Spark/Projects/GitHub_APPS/RecurringExpenseTracker`，分支 `feat/tilla-ui`（HEAD `3c4d3b9`，`main..feat/tilla-ui` 共 10 个 Tilla UI patch）。
下文所有路径均相对仓库根；行号基于该 HEAD（2026-10-24）。工作区根：`/home/Spark/Projects/GitHub_APPS`。
结论先行：**无 paused/skip 概念**；所有"发生日"由 `RecurringExpenseData.getNextPaymentDayAfter(LocalDate)` 即时计算，不落库；**日历不需要改 schema**（只读），需要区间查询时再加 DAO 方法。

## 1. 模块 / 文件索引

Gradle：`:app`（Android application）+ `:shared`（KMP library）。`shared` 目标：`android`（`com.android.kotlin.multiplatform.library`）、`jvm()`、`iosArm64`/`iosSimulatorArm64`（静态 framework `App`），源集 `commonMain/androidMain/iosMain/jvmMain/commonTest/jvmTest`。
关键依赖：Kotlin 2.4.20、Compose MP 1.12.1、Material3 1.9.0、navigation-compose 2.9.2（JetBrains 版，type-safe route）、lifecycle-viewmodel 2.11.0、Koin 4.2.2、Room **3.0.3**（包名 `androidx.room3`，KSP）、kotlinx-datetime **0.8.0**、kotlinx-serialization 1.11.0。`gradle/libs.versions.toml`。

| 路径（相对仓库根） | 角色 |
|---|---|
| `app/src/main/kotlin/.../MainActivity.kt` | Android 入口：`AppCompatActivity`、edge-to-edge、主题、备份/恢复 launcher、`MainContent` 唯一参数装配 |
| `app/src/main/kotlin/.../ExpenseTrackerApplication.kt` | `startKoin(sharedModule, platformModule, appModule)`；Glance widget 预览延后 5s |
| `app/src/main/kotlin/.../di/AppModule.kt` | Android 专用 DI：AlarmManager / NotificationManager / widget model |
| `app/src/main/kotlin/.../model/DatabaseBackupRestore.kt` | 原生 SQLite 文件 zip 导出/导入（关库→替换文件→重启进程） |
| `app/src/main/kotlin/.../model/notification/*.kt` | 通知轮询 `NotificationLoopReceiver`（`autoArchiveExpired` 调用点）、`AlarmLoopReceiver`、`SystemNotificationBuilder` |
| `app/src/main/kotlin/.../widget/*.kt` | Glance 桌面小组件（复用 `UpcomingPaymentsExpander.collectFuturePayments`） |
| `app/src/main/kotlin/.../security/BiometricPromptManager.kt` | 生物识别 |
| `app/src/main/kotlin/.../viewmodel/MainActivityViewModel.kt` | 仅 `isUnlocked` Compose state |
| `shared/src/commonMain/.../data/Navigation.kt` | 全部 Nav 路由（`@Serializable`）+ `BottomNavigation` + `isInRoute` |
| `shared/src/commonMain/.../data/RecurringExpenseData.kt` | 领域模型 + 到期日 API + `Recurrence` enum |
| `shared/src/commonMain/.../data/UpcomingPaymentData.kt` | Upcoming/日历可复用的"一次付款"UI 数据 |
| `shared/src/commonMain/.../data/ExpenseService.kt` | Tilla 品牌图标/配色目录（92 项）+ 分类 |
| `shared/src/commonMain/.../data/ExpenseServiceNameMatcher.kt` | 由名称推断 serviceId（单词级匹配） |
| `shared/src/commonMain/.../data/ExpenseTemplate.kt` / `Tag.kt` / `Reminder.kt` / `CurrencyValue.kt` | 快捷模板 / 标签 / 提醒 / 金额值对象 |
| `shared/src/commonMain/.../model/DateTimeCalculator.kt` | 日期差、下一次发生日（唯一日期算术语义） |
| `shared/src/commonMain/.../model/UpcomingPaymentsExpander.kt` | 按月展开 occurrence（auto-advance / manual / past） |
| `shared/src/commonMain/.../model/database/RecurringExpenseEntry.kt` | Room 实体 + `RecurrenceDatabase` int 映射 + `getMonthlyPrice()` |
| `shared/src/commonMain/.../model/database/RecurringExpenseDao.kt` | 全部 SQL / Flow |
| `shared/src/commonMain/.../model/database/RecurringExpenseDatabase.kt` | `@Database(version=12)`、expect constructor、12 级迁移 |
| `shared/src/commonMain/.../model/database/IExpenseRepository.kt` / `ExpenseRepository.kt` | 仓库接口/实现 |
| `shared/src/commonMain/.../model/database/*Mapper.kt` | Entity ↔ 领域模型（含 serviceId 回退推断） |
| `shared/src/commonMain/.../model/database/{TagEntry,ExpenseTagCrossRefEntry,ReminderEntry,PaymentRecordEntry}.kt` | 其余 4 张表 |
| `shared/src/commonMain/.../model/database/AutoArchiveCandidate.kt` / `FakePreviewExpenseRepository.kt` | 自动归档投影 / @Preview 假仓库 |
| `shared/src/commonMain/.../model/datastore/*.kt` | KSafe 偏好（`IUserPreferencesRepository` + 12 个 key） |
| `shared/src/commonMain/.../model/notification/ExpenseNotificationManager.kt` | 通知内容（用 `getNextPaymentDay()`） |
| `shared/src/commonMain/.../di/Module.kt` | `sharedModule`（VM/仓库/DB）+ `previewModule` |
| `shared/src/commonMain/.../ui/MainContent.kt` | 全部目的地的 NavHost + Scaffold + 顶栏/底栏/FAB |
| `shared/src/commonMain/.../ui/BottomNavBar.kt` | 3 项底栏（Home/Upcoming/Settings） |
| `shared/src/commonMain/.../ui/DefaultTab.kt` | 默认 tab 持久化枚举 Home(0)/Upcoming(1) |
| `shared/src/commonMain/.../ui/TillaExpenseCard.kt` | Tilla 色块卡片 + 配色/图标 API + 汇总卡 |
| `shared/src/commonMain/.../ui/RecurringExpenseOverview.kt` | Home 列表/瀑布流 |
| `shared/src/commonMain/.../ui/upcomingexpenses/UpcomingPaymentsScreen.kt` | Upcoming 屏（月份分段/已付分隔/标记） |
| `shared/src/commonMain/.../ui/editexpense/*.kt` | 编辑器 15 个 option + `ServiceOption` 图标选择器 + `QuickTemplatesRow` |
| `shared/src/commonMain/.../ui/settings/*.kt` | 设置主屏 + 内嵌 NavHost（About/Libraries/Currency） |
| `shared/src/commonMain/.../ui/theme/{Theme,Color,PreviewTheme}.kt` | M3 配色（非 Tilla 色）+ @Preview Koin 包装 |
| `shared/src/commonMain/.../viewmodel/*.kt` | 6 个 VM（普通 `ViewModel` + Compose state） |
| `shared/src/commonMain/composeResources/values*/strings.xml` | Compose Resources 字符串（含 `values-zh-rCN`、`values-zh-rTW`） |
| `shared/schemas/de.dbauer.expensetracker.shared.model.database.RecurringExpenseDatabase/{2..12}.json` | Room schema 导出（KSP 生成，需提交） |
| `shared/src/commonTest/...` | 11 个测试类、126 个 jvm 测试 |
| `app/src/main/AndroidManifest.xml` | `enableOnBackInvokedCallback=true`、权限、receiver |

## 2. 数据层

### 2.1 `recurring_expenses`（`RecurringExpenseEntry.kt:7-46`）

| 字段 | 类型/可空 | 说明 |
|---|---|---|
| `id` | `Int` 非空 PK autoGenerate | 领域 `RecurringExpenseData.id`；新建用 0 |
| `name` | `String?` | mapper 用 `!!`（DB 旧数据理论上可空） |
| `description` | `String?` | 同上 |
| `price` | `Float?` | 非 BigDecimal；金额精度靠显示层 |
| `everyXRecurrence` | `Int?` | "每 X 个周期"，UI 默认 1 |
| `recurrence` | `Int?` | 1 Daily / 2 Weekly / 3 Monthly / 4 Yearly（`RecurrenceDatabase` `:48-55`；enum 在 DB 层是 internal） |
| `firstPayment` | `Long?` | **UTC 当日 0 点** epoch millis（DatePicker 语义）；为 null ⇒ 永无 occurrence |
| `currencyCode` | `String` 非空 | **空串 = 用默认货币**（mapper `RecurringExpenseWithTagsMapper.kt:16`） |
| `notifyForExpense` | `Boolean` 非空 | 通知开关 |
| `requireManualConfirmation` | `Boolean` 非空默认 false | true ⇒ 每次 occurrence 需手动 "mark as paid"（`payment_records`） |
| `endDate` | `Long?` | **含当日**的最后一次发生日上界（UTC 0 点） |
| `archivedDate` | `Long?` | 归档时间；非空即从 active 查询中消失 |
| `serviceId` | `String?`（v12 新增） | Tilla 图标/配色 key；null 时 mapper 用名称推断 |

`getMonthlyPrice()`（`:23-45`）：Daily `(365/12)/x*price`、Weekly `(52/12)/x*price`、Monthly `1/x*price`、Yearly `price/(12x)`，unknown 返回 0。
金额与标签：`CurrencyValue(value: Float, currencyCode: String, isExchanged: Boolean)`；`Tag(title: String, color: Long, id: Int=0)`。
其余表：`tags(id,title,color:Long)`；`ExpenseTagCrossRef(expenseId,tagId)` 复合 PK + 双向索引；`reminders(id,expenseId→CASCADE,daysBeforePayment,lastNotificationDate)`；`payment_records(id,expenseId→CASCADE,paymentDate)`（**唯一表示"某次已付"的持久化状态**，值为 UTC 当日 0 点 epoch）。**没有 dueDate/paid 列，也没有 occurrence 表。**

### 2.2 领域模型 `RecurringExpenseData`（`RecurringExpenseData.kt:30-73`）

`id:Int, name:String, description:String, price:CurrencyValue, monthlyPrice:CurrencyValue, everyXRecurrence:Int, recurrence:Recurrence, tags:List<Tag>, firstPayment:Instant?, notifyForExpense:Boolean, reminders:List<Reminder>=empty, requireManualConfirmation:Boolean=false, endDate:Instant?=null, archivedDate:Instant?=null, serviceId:String?=null`。
注意：`Instant` 是 **`kotlin.time.Instant`**（0.8.0 起 kotlinx-datetime 已迁移）；`Recurrence` enum（`:20-28`）带 `fullStringRes/shortStringRes`（Compose Resources `StringResource`），日历如需"每月/每年"文案可直接复用。

### 2.3 DAO（`RecurringExpenseDao.kt`）

Flow 列表（全部 `@Transaction` + `@Relation` 预取 tags/reminders）：`getAllExpenses()` `:15`、`getAllExpensesByPrice()` `:19`、`getAllArchivedExpenses()` `:23`、`getAllArchivedExpensesByPrice()` `:27` —— 均 `WHERE archivedDate IS NULL`（或 `IS NOT NULL`），**没有日期区间查询**。
单条：`getExpenseById(id): RecurringExpenseWithTagsEntry?` `:31`。
写：`insert/update/delete(RecurringExpenseEntry)` `:61-68`、`setArchivedDate(id, archivedDate?)` `:34`、`getExpensesToAutoArchive(now)` `:40`、`clearEndDateIfOverdue(id, now)` `:45`。
标签/提醒/付款：`insertPaymentRecord` `:106`、`deletePaymentRecord` `:109`、`getPaymentRecordsForExpense(expenseId)` `:112`、`getPaymentRecord(expenseId,paymentDate)` `:115`、`deleteAllPaymentRecordsForExpenseId` `:121`。

### 2.4 数据库版本 / 迁移

`@Database(entities=[RecurringExpenseEntry, TagEntry, ExpenseTagCrossRefEntry, ReminderEntry, PaymentRecordEntry], version=12)`（`RecurringExpenseDatabase.kt:19-28`），`ksp` + expect/actual `RecurringExpenseDatabaseConstructor`（`:15`）。
builder `getRecurringExpenseDatabase(builder)`（`:34-50`）：依次 `migration_1_2 … migration_11_12`、`fallbackToDestructiveMigrationOnDowngrade(true)`、`setQueryCoroutineContext(Dispatchers.IO)`。
`migration_11_12`（`:344-351`）= `ALTER TABLE recurring_expenses ADD COLUMN serviceId TEXT DEFAULT NULL`；`migration_10_11`（`:333-343`）加 `endDate`/`archivedDate`；`migration_9_10`（`:312-332`）加 `requireManualConfirmation` + `payment_records` 表。
Schema 导出：`shared/build.gradle.kts` `room3 { schemaDirectory("$projectDir/schemas") }`；JSON 在 `shared/schemas/de.dbauer.expensetracker.shared.model.database.RecurringExpenseDatabase/12.json`（identityHash `45eb1c00617a200beada29e95c3b07b8`）。改实体必须 bump version + 加迁移 + 提交新 JSON（KSP 在构建时生成 `copyRoomSchemas`）。

### 2.5 仓库

`IExpenseRepository`（`IExpenseRepository.kt:7-53`，17 个成员）：4 个 active/archived Flow、`allTags`、`getRecurringExpenseById`、CRUD、`archive/unarchive`、`clearEndDateIfOverdue`、`autoArchiveExpired`、tag CRUD、`markAsPaid/markAsUnpaid(expenseId, paymentDateEpoch)`、`getPaymentRecordsForExpense`。
`ExpenseRepository`（`ExpenseRepository.kt`）：Flow 用 `map { toRecurringExpenseData(defaultCurrency) }`（`:18-35`）；所有写操作 `withContext(Dispatchers.IO)`；insert/update 手动维护 tag crossref 与 reminders（`:46-95`）；`markAsPaid` 先查重再插（`:149-159`）；默认货币来自 `defaultCurrency.first().ifBlank { getSystemCurrencyCode() }`（`:176-178`）。
**实现者清单（加接口方法会编译失败，需同步）**：`ExpenseRepository`、`FakePreviewExpenseRepository`（commonMain，preview 用）、`FakeExpenseRepository`（commonTest）、`UpcomingPaymentsViewModelTest` 内匿名 object（`:31`）；DAO 实现者另有 `ExpenseRepositoryReminderTest.FakeRecurringExpenseDao`（`:23`）。

### 2.6 备份 / 恢复（与新增字段的关系）

`DatabaseBackupRestore` 备份 = 把 `recurring-expenses`、`-wal`、`-shm` 三文件打进 zip（`:91-110`）；恢复 = 解压到 cacheDir → `database.close()` → 删除目标目录 sidecar（`-wal/-shm/-journal`）→ 复制文件（`:46-89`）。**没有字段级映射**：新字段/新表只需保证"旧 schema 文件能被本版本迁移打开"。`MainActivity` 随后 `restartApplicationProcess()`（`:358-379`，AlarmManager 800ms + `exitProcess(0)`），因为官方备份是 v11、本 build 是 v12。⇒ 日历若**只读**：无需改动；若加表/列：必须写 `migration_12_13`（含 `CREATE TABLE`），否则恢复 v12 备份后崩溃。

## 3. 重复 / 到期日语义（精确）

所有函数都以 `LocalDate` 为"发生日"载体，**occurrence 不落库**。

`DateTimeCalculator`（`model/DateTimeCalculator.kt`）：
- `fun getDaysFromNowUntil(until: LocalDate): Int`（`:15`）→ 调 `getDaysFromUntil(getCurrentLocalDate(), until)`。
- `fun getDaysFromUntil(from: LocalDate, until: LocalDate): Int`（`:19-25`）→ `from.atStartOfDayIn(TimeZone.UTC).daysUntil(until.atStartOfDayIn(TimeZone.UTC), TimeZone.UTC)`；同一天 0，过去为负。
- `fun getDayOfNextOccurrenceFromNow(from: Instant, everyXRecurrence: Int, recurrence: DateTimeUnit.DateBased): LocalDate`（`:27-33`）。
- `fun getDayOfNextOccurrence(afterDay: LocalDate, first: Instant, everyXRecurrence: Int, recurrence: DateTimeUnit.DateBased): LocalDate`（`:35-47`）：
  `next = first.toLocalDateTime(TimeZone.UTC).date`；`while (afterDay 严格晚于 next) next = next.plus(everyXRecurrence, recurrence)`；**返回第一个 ≥ afterDay 的发生日（含当日）**。
- `fun getCurrentLocalDate(now: Instant = Clock.System.now()): LocalDate`（`:49-54`）→ `now.toLocalDateTime(TimeZone.currentSystemDefault()).date`（**全仓库唯一使用系统时区的日期入口**，UI "today" 与通知用它）。

`RecurringExpenseData`：
- `fun getNextPaymentDay(): LocalDate?`（`:47-58`）→ 用 "now" 起算；`firstPayment == null` 或 `next > endDate（UTC 日）` 返回 null。
- `fun getNextPaymentDayAfter(afterDate: LocalDate): LocalDate?`（`:60-72`）→ 同上但锚定 `afterDate`；**endDate 含当日**（同日仍返回，见 `RecurringExpenseDataTest:14-23`）。
- `private fun Recurrence?.toDateTimeUnit(): DateTimeUnit.DateBased`（`:75-83`）：Daily→`DAY`、Weekly→`WEEK`、Monthly→`MONTH`、Yearly→`YEAR`、null→`MONTH`（private，日历需复制/提升可见性）。

`UpcomingPaymentsExpander`（`model/UpcomingPaymentsExpander.kt`，都被 `object` 静态调用）：
- `fun expandAutoAdvance(expense, yearMonthIterator: LocalDate, from: LocalDate, payments: MutableList<UpcomingPaymentData>)`（`:16-50`）：先把 `nextPaymentDay` 推进到 ≥ `from`（月内过去的自动扣款视为已发生），再在 `isSameMonth(yearMonthIterator)` 内逐个 `+1 DAY` 重算；`requiresConfirmation=false, isPaid=false`。**没有 endDate 之外的跳过机制。**
- `fun expandManualConfirmation(expense, yearMonthIterator, from, paidDates: Set<Long>, unpaidPayments, paidPayments)`（`:52-94`）：月内全部 occurrence；`isPaid = paidDates.contains(paymentDateEpoch)`；未付项仅当 `remainingDays >= 0 || 当前月 == from 所在月` 才输出（⇒ 当月逾期项仍可见）。
- `fun expandPastMonth(expense, yearMonthIterator, from, paidDates, unpaidPayments, paidPayments)`（`:96-141`）：过去月（或当月按 "past" 处理时）auto-advance 全部记 unpaid；manual 项按 paid 记录分流。
- `suspend fun collectFuturePayments(expenseRepository, recurringExpenses, from, until): List<UpcomingPaymentData>`（`:148-198`）：**仅未来**平铺列表，供桌面小组件；`filter { it.archivedDate == null }`（`:154`）。
- `paymentDateEpoch = nextPaymentDay.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()`（`:32,63,107`）——日历若要与 "已付" 对齐，必须用同样换算。

"下一次/upcoming 列表"如何产生：`UpcomingPaymentsViewModel`（`viewmodel/UpcomingPaymentsViewModel.kt`）
- 构造：`(expenseRepository, exchangeRateProvider, userPreferencesRepository)`（`:60-64`）；`init` 里 `combine(allRecurringExpensesByPrice, upcomingPaymentHorizonMonths)` → `onDatabaseUpdated`（`:75-85`）。
- `onDatabaseUpdated(expenses, horizonMonths)`（`:124-145`）：`from = 今天（系统时区）`，`until = from.plus(DatePeriod(months = horizon))`，`pastMonths = PAST_MONTHS_COUNT = 3`（`:377`），算 `upcomingStartIndex`（首个 `UpcomingDivider` 的下标），用 `applyListUpdate` 原地更新（`:147-160`）。
- `suspend fun createUpcomingPaymentData(expenses, from, until, pastMonths: Int = 0): List<UpcomingPayment>`（`:162-323`）：`withContext(Dispatchers.IO)`；预取 manual 项的付款记录（`:182-186`）；从 `LocalDate(from.year, from.month, 1)` 逐月到 `until`（月首，不含）循环；`isPastMonth = yearMonthIterator < from`（**注意：from 在月中时当月会走 past 分支**，因此当月月初的自动扣款也会出现，正好适合日历）；每月按 `nextPaymentRemainingDays` 排序，产出 `MonthHeader` + unpaid + `PaidDivider?` + paid（`addMonthSection :325-347`），最后插一个 `UpcomingDivider`（`:290-305`）。
- 输出模型 `sealed class UpcomingPayment`（`:32-58`）：`MonthHeader(month,sum,isPastSection)`、`PaymentItem(month,sum,payment)`、`PaidDivider`、`UpcomingDivider`；`UpcomingPaymentData`（`data/UpcomingPaymentData.kt:3-16`）字段 `id,name,price,nextPaymentRemainingDays,nextPaymentDate:String(已格式化),tags,requiresConfirmation,isPaid,paymentDateEpoch`，派生 `isOverdue`。
- 未来 occurrence 数量：受 `upcomingPaymentHorizonMonths`（默认 120，UI `UpcomingHorizon` 1/3/6/12/24/60/120，`ui/UpcomingHorizon.kt`）限制；每月内不设上限；过去固定 3 个月。
- 跳过/暂停：**无**。只有 `archivedDate`（DAO 已过滤 / `collectFuturePayments` 过滤）与 `endDate`（含当日，之后无 occurrence）；`endDate` 到期的自动归档只发生在通知轮询 `NotificationLoopReceiver.kt:69` → `autoArchiveExpired(now)`。

## 4. UI / 导航

`MainActivity`（`MainActivity.kt`）：`AppCompatActivity`（`:81`）、`enableEdgeToEdge()`（`:103`）、`setContent`（`:201`）、`ExpenseTrackerTheme`（`:280`）、`MainContent(...)`（`:304-351`，参数含 `startRoute`、`deferredStartRoute`）。默认 tab 不阻塞首帧：`savedInstanceState == null` 时异步读 `defaultTab`，仅当 `Upcoming` 才把 `deferredStartRoute = UpcomingPane`（`:172-179`），`MainContent` 用 `LaunchedEffect` 导航并 `popUpTo(start){inclusive=true}`（`MainContent.kt:148-157`）。
Insets：Scaffold 默认 `contentWindowInsets` 吸收状态栏/导航栏，`NavHost` 内容由各屏 `Modifier.padding(paddingValues)` 消费（如 `:420`）；Home 键盘打开时只取 start/end/top + `imePadding()`（`:332-355`）；列表底部留 80dp 让 FAB（`:333`、`UpcomingPaymentsScreen:231-238`）。系统返回由 NavHost + `android:enableOnBackInvokedCallback="true"`；**屏内返回**用 `navigationevent-compose` 的 `NavigationBackHandler`（Home 搜索 `MainContent:214-225`、编辑页未保存 `EditRecurringExpenseScreen:69-77`）。

导航图（`data/Navigation.kt:15-43`，type-safe serializable）：

| route | 类型 | composable 位置 | VM / 依赖 | topBar 设置 |
|---|---|---|---|---|
| `HomePane(showArchived=false)` | data class | `MainContent.kt:200` | `koinViewModel<RecurringExpenseViewModel>()` | 就地设置（搜索态切换 `SearchBar`）`:229-330` |
| `UpcomingPane` | object | `:394` | `UpcomingPaymentsScreen` 内 `koinViewModel<UpcomingPaymentsViewModel>()` | 就地 `TopAppBar` + `ToggleGridModeButton` `:395-409` |
| `SettingsPane` | object | `:423` | `SettingsScreen` 内嵌 NavHost（About/Libraries/DefaultCurrency，`SettingsScreen.kt:71-102`） | `setTopAppBar` 回调 `:436-442` |
| `EditExpensePane(expenseId:Int?=null)` | class | `:444` | `koinViewModel { parametersOf(expenseId) }` | `setTopAppBar` 回调 `:499-505` |
| `TagsPane` | object | `:507` | `koinViewModel<TagsScreenViewModel>()` | `setTopAppBar` |
| `WhatsNew` | object | `:522` | `koinInject<IWhatsNew>()` | 就地 |

顶栏模型：`MainNavigationViewModel.topAppBar: mutableStateOf<@Composable () -> Unit>`（`viewmodel/MainNavigationViewModel.kt:17`），由 `MainContent` 的 `Scaffold(topBar = { mainNavigationViewModel.topAppBar() })`（`:163`）渲染 ⇒ **每个目的地必须重新赋值**，否则沿用上一屏顶栏。
底栏：仅 Home/Upcoming/Settings 显示（`MainContent.kt:171-177`）；`BottomNavBar` 列表硬编码 3 项（`BottomNavBar.kt:34-39`），点击 `popUpTo(start){saveState=true}` + `launchSingleTop` + `restoreState`（`:48-62`）；`selected` 用 `hasRoute(item.route::class)`（`:43`）。FAB：仅 Home/Upcoming 显示（`MainContent.kt:179`），Tilla 薄荷色 `0xFF2EE6D6` / 内容 `0xFF00201D`，点击 `navigate(EditExpensePane())`（`:141-143,180-193`）。

Home / Tilla 卡片 API（`ui/TillaExpenseCard.kt`）：
- `@Composable fun TillaExpenseCard(expense: RecurringExpenseData, today: LocalDate, onClick: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false)`（`:118-216`）。所有日期/图标/文案都在 `remember(expense, today)` 里算（`:125-131`，对应 patch 840508d）。
- 私有 `data class TillaCardStyle(service: ExpenseService?, background: Color, foreground: Color, iconTint: Color, descriptionColor: Color, labelColor: Color)`（`:218-225`）；私有 `fun buildCardStyle(serviceId: String?, name: String): TillaCardStyle`（`:227-243`）：`ExpenseServiceCatalog.find(serviceId)` → `background = service?.backgroundColor ?: fallbackCardColor(name)`；`foreground = service?.foregroundColor ?: (luminance>0.5 ? Color(0xFF151719) : White)`；`iconTint = service?.iconTint ?: foreground`；description/label 用 foreground 的 0.72/0.78 alpha。
- `internal fun fallbackCardColor(name)`（`:71-74`）+ `fallbackCardColors` 8 色（`:59-69`）：按 `name.hashCode()` 取模，稳定。
- `@Composable fun ExpenseServiceIconView(service: ExpenseService?, tint: Color, modifier)`（`:77-109`，public）：`ExpenseServiceIcon.Brand(DrawableResource)` → `painterResource`，`.Material(ImageVector)` → `Icon`，null → `Icons.Rounded.Category`。
- 页面底色：`TillaBackgroundLight = 0xFFEFF6F2`、`TillaBackgroundDark = 0xFF0E1513`（`:56-57`）；`MainContent` 按 `background.luminance()` 选（`MainContent.kt:134-139`）并作为 `Scaffold.containerColor`（`:161`）。卡片圆角 `RoundedCornerShape(26.dp)`（`:53`）。汇总卡 `TillaExpenseSummary(monthly, weekly, yearly)`（`:292-337`）。
- 图标/配色目录：`ExpenseService(id, name, category, backgroundColor, foregroundColor?, iconTint?, icon)`（`data/ExpenseService.kt:128-136`），92 项；`ExpenseServiceCatalog.find(id)`、`forCategory()`、`search()`（`:860-870`）；`ExpenseServiceIcon`/`ExpenseServiceCategory`（`:101-119`）。

Home 列表：`RecurringExpenseOverview(isGridMode, navController, ...)`（`ui/RecurringExpenseOverview.kt:38-46`）；`today` 每屏算一次（`:70`）；grid = `LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(170.dp))` + summary 整行（`:96-133`）；list = `LazyColumn`（`:134-170`，patch 4d34e6a）；点击 `navigate(EditExpensePane(id))`（`:73-75`）。

Upcoming 屏（`ui/upcomingexpenses/UpcomingPaymentsScreen.kt`）：`UpcomingPaymentsScreen(isGridMode, navController, modifier, contentPadding, upcomingPaymentsViewModel = koinViewModel())`（`:74-108`）；空态 placeholder；`LazyVerticalStaggeredGrid`（grid 用 `Adaptive(160.dp)`，list 用 `Fixed(1)`）（`:138-150`）；item key 规则 `header_*` / `expense_${month}_${id}_${date}` / paid_divider / upcoming_divider（`:153-173`）；列表初始滚动到 `upcomingStartIndex`（`:121-124`）。已付/未付图标与 overdue 配色见 `:244-285,393-600`。
编辑器：`EditRecurringExpenseScreen(expenseId, canUseNotifications, onDismiss, onArchived, onUnarchived, onEditTagsClick, setTopAppBar, modifier, viewModel)`（`ui/editexpense/EditRecurringExpenseScreen.kt:58-68`）；日期选择已有现成范式：`FirstPaymentOption` / `EndDateOption` 用 Material3 `DatePickerDialog` + `rememberDatePickerState`（`FirstPaymentOption.kt:51,98-118`），选中值按 UTC 0 点处理（`:121-128`）。

### 新增 Calendar destination 的插入点

方案 A（推荐，与 Tilla 一致）：**第 4 个底部 tab** `CalendarPane`。
1. `data/Navigation.kt`：`@Serializable object CalendarPane : MainNavRoute`（放 `:22` 附近）。
2. `ui/BottomNavBar.kt:34-39`：加 `BottomNavigation(CalendarPane, Res.string.bottom_nav_calendar, Icons.Rounded.CalendarMonth)`（Material3 `NavigationBar` 支持 3–5 项，4 项仍可读）。
3. `ui/MainContent.kt`：新增 `composable<CalendarPane> { ... }`（照抄 Upcoming `:394-422` 的 topBar 赋值方式），并把它加入底栏可见性列表 `:173` 与 FAB 列表 `:179`（否则底栏/FAB 消失）。
4. `ui/DefaultTab.kt`：`Calendar(2)`；`SettingsMainScreen.kt` 默认 tab 对话框（`:136-146` 副标题、`:308-330` 选项）加一项；`MainActivity.kt:172-179` 把 `DefaultTab.Calendar` 映射为 `CalendarPane`。**枚举值不可变**（KSafe 持久化 `DEFAULT_TAB` int，`DefaultTab.fromInt` 用 `entries.first`，未知值会抛异常）。
5. 字符串 `bottom_nav_calendar`（`values/strings.xml` + `values-zh-rCN`）。
优点：与 Tilla 原设计一致（APK 内 `com.pavlorekun.tilla.feature.calendar.CalendarNavigationRoute`、`navigateToCalendar(NavController)`、`res/drawable/ic_navigation_calendar.xml`，见 `.research/tilla/`）；日历拥有独立顶栏/返回栈，month→day detail 可用 NavHost 内嵌或屏内 state + `NavigationBackHandler`；缺点：改动点 5 处文件 + 默认 tab 逻辑。

方案 B（最小侵入）：**UpcomingPane 内加"列表 / 月历"切换**。
- Upcoming 顶栏已有 `ToggleGridModeButton`（`MainContent.kt:402-407`），可换成 `SingleChoiceSegmentedButtonRow` 或再加一个 `IconToggleButton`；日历状态留在 `UpcomingPaymentsViewModel`（或独立 `CalendarViewModel` 仅被该 composable 使用）。
- 优点：不动 `Navigation.kt`/`BottomNavBar`/`DefaultTab`/`MainActivity`，无第 4 tab 挤压，天然共享 horizon 与月份滚动；缺点：顶栏拥挤、`gridMode` 与"日历/列表"语义冲突（`gridMode` 是全局偏好，见 `IUserPreferencesRepository:17`）、day detail 的返回需要手写 `NavigationBackHandler`、发现性差。
结论：若目标是"Tilla 风格日历页"而不是最小 patch，选 **A**；若只求快且不想碰默认 tab 持久化，选 **B**（改动约一半）。

## 5. 格式化 / i18n

- 日期：`java.time` 只在 androidMain/jvmMain 的 `Extensions.*.kt` actual 中出现，commonMain 只有 kotlinx-datetime。expect 声明在 `shared/.../Extensions.kt`：`Float.toCurrencyString(currencyCode)`（`:17`）、`Float.toLocalString()`（`:19`）、`String.toFloatLocaleAware()`（`:21`）、`Instant.toLocaleString()`（`:34`）、`LocalDate.toMonthYearStringUTC()`（`:36`）、`StringResource.asString()`（`:38`）。
- 月份名：`toMonthYearStringUTC()` → Android/JVM `Month.of(month.number).getDisplayName(TextStyle.FULL, Locale.getDefault()) + " $year"`（`Extensions.android.kt:68-75`，desktop 同）；iOS `NSDateFormatter.dateFormat = "MMMM"`（`Extensions.ios.kt:46-60`）。日历月份头应复用它，**不要**直接 `LocalDate.toString()`。
- 星期名 / 周首日：**仓库里没有**任何 weekday 名称或 first-day-of-week 逻辑（也没有 `WeekFields`）。推荐：7 个字符串 key（`calendar_weekday_mon_short` …）放进 `strings.xml`，周首日写死 ISO 周一（`DayOfWeek.isoDayNumber`，1=Mon）或加 `expect fun firstDayOfWeekIso(): Int`（Android/JVM `WeekFields.of(Locale.getDefault()).firstDayOfWeek.value`，iOS `NSCalendar.firstWeekday`）。阿拉伯语（`values-ar`）通常周首日不同，若在意需 expect/actual。
- 货币：`CurrencyValue.toCurrencyString()` = `value.toCurrencyString(currencyCode)`（`CurrencyValue.kt:5-10`，`isExchanged` 时加 `~`）；平台实现用 `NumberFormat`/`NSNumberFormatter`（非法 code 回退 `"%.2f CODE"`）。
- 字符串：Compose Resources，每语言一个 `shared/src/commonMain/composeResources/values*/strings.xml`（base 179 个 `<string>` + 3 个 `<plurals>`），没有 per-key 文件；生成包名 `recurringexpensetracker.shared.generated.resources`，用 `stringResource(Res.string.xxx)` / `pluralStringResource(Res.plurals.home_card_days, count, count)`；`translatable="false"` 用于品牌名（如 `app_name`、`home_title`）。中文已有 `values-zh-rCN`（157 行，Tilla 相关 key 已译）与 `values-zh-rTW`。
- 现有日期选择组件：Material3 `DatePicker`/`DatePickerDialog`（`FirstPaymentOption.kt`、`EndDateOption.kt`）；`TimePickerDialog`（`ui/elements/TimePickerDialog.kt`，通知时间用）。无自定义 CalendarView/月历组件可复用。

## 6. 状态 / 架构约定与测试

- VM 基类：无，全部 `androidx.lifecycle.ViewModel`（JetBrains multiplatform 版）+ `viewModelScope`。UI 状态是 **Compose snapshot state**：`mutableStateOf` / `mutableStateListOf` / `mutableFloatStateOf`（例：`RecurringExpenseViewModel:29-42`、`UpcomingPaymentsViewModel:65-73`）；只有 `showArchived` 用 `StateFlow`（`RecurringExpenseViewModel:38-39`）。列表更新时**原地改**以保住 Lazy 滚动位置（`applyListUpdate` `UpcomingPaymentsViewModel:147-160`）。
- DI：Koin。`sharedModule`（`di/Module.kt:33-50`）注册 `ExpenseRepository.bind<IExpenseRepository>()`、DB、DAO、6 个 VM（`viewModelOf`/`viewModel { (param: Int?) -> ... }`）、`CurrencyProvider`、`IExchangeRateProvider`、`ExpenseNotificationManager`、`IWhatsNew`；`previewModule`（`:52-66`）给 @Preview 用假实现（**注意它没有注册 `UpcomingPaymentsViewModel`**，日历 VM 若要 preview 必须补）。Composable 里 `koinViewModel<T>()` / `koinInject<T>()`；带参 VM 用 `koinViewModel { parametersOf(id) }`（`EditRecurringExpenseScreen:67`）。
- @Preview：`ExpenseTrackerThemePreview { ... }` 包 `KoinApplicationPreview(previewModule)`（`ui/theme/PreviewTheme.kt:8-23`）；各屏文件底部有 `@Preview` 私有函数（如 `UpcomingPaymentsScreen:646,751`、`MainContent:545`）。
- 测试：`commonTest`（`kotlin-test` + `kotlinx-coroutines-test`）在 jvm 上执行，共 **126** 个（`ExpenseServiceNameMatcherTest 12`、`EditRecurringExpenseViewModelTest 33`、`UpcomingPaymentsViewModelTest 28`、`ExpenseNotificationManagerTest 20`、`DateTimeCalculatorTest 5`、`RecurringExpenseTest 8`、`ExpenseRepositoryReminderTest 8`、`ReminderMapperTest 6`、`RecurringExpenseDataTest 4`、`RecurringExpenseDatabaseMigrationTest 1`、`LegacyPreferencesMigrationTest 1`）。
  与日历日期逻辑最相关：`DateTimeCalculatorTest`（daily/weekly/monthly 月末 clamp/yearly，`:26-145`）、`RecurringExpenseDataTest`（endDate 含当日/越界 null）、`UpcomingPaymentsViewModelTest`（按月的 occurrence 数量、divider、paid/unpaid、horizon、past months）。测试直接用 `viewModel.createUpcomingPaymentData(...)` 这种"公开的 suspend 内部函数"，新日历月份投影函数也应保持可注入/可单测。测试里已有 `FakeExpenseRepository`（commonTest:8）与 `FakeUserPreferencesRepository`。

## 7. 日历数据流提案（查询 → 按本地日期分组 → 渲染）

不改 schema 的最小闭环：
1. 选中月 `monthStart: LocalDate = LocalDate(y, m, 1)`；`monthEnd = monthStart.plus(1, DateTimeUnit.MONTH)`。
2. 取 `expenseRepository.allRecurringExpenses`（已排除 archived；若要在日历里显示 archived，需另取 `allArchivedRecurringExpenses` 并区分样式）。
3. 每个 expense 枚举：`var d = expense.getNextPaymentDayAfter(monthStart) ?: continue; while (d != null && d < monthEnd) { add(...); d = expense.getNextPaymentDayAfter(d.plus(1, DateTimeUnit.DAY)) }` —— 与 Upcoming 完全同源（含 clamp 漂移）；`endDate` 由函数内部处理。
4. 已付状态：仅 `requireManualConfirmation` 需要；`paidDates = expenseRepository.getPaymentRecordsForExpense(id).toSet()`，判定 `paidDates.contains(d.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())`。
5. 分组：`occurrences.groupBy { it.date }`；单元格 day 序列 `generateSequence(monthStart) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it < monthEnd }`；首格偏移 `(monthStart.dayOfWeek.isoDayNumber - firstDayOfWeekIso + 7) % 7`。渲染 marker 用 `buildCardStyle` 的颜色（建议把它从 `private` 提为 `internal fun tillaCardStyleOf(serviceId, name)`），或用 `ExpenseServiceCatalog.find(serviceId)?.backgroundColor ?: fallbackCardColor(name)`（后者已是 `internal`，commonMain 同模块可用）。
6. 日详情：复用 `TillaExpenseCard`（需要完整 `RecurringExpenseData`，`getRecurringExpenseById` 或让投影携带必要字段）或轻量行 + `ExpenseServiceIconView`；操作复用 `markAsPaid/markAsUnpaid`（VM `:98-116`）。
7. VM：`CalendarViewModel(expenseRepository, userPreferencesRepository)`，`combine(allRecurringExpenses, selectedMonth) { ... }` → `val occurrencesByDay: Map<LocalDate, List<CalendarOccurrence>>`；`CalendarOccurrence(expenseId: Int, date: LocalDate, name: String, price: CurrencyValue, serviceId: String?, isPaid: Boolean, requiresConfirmation: Boolean)`；投影逻辑放 `model/` 的纯 `suspend fun`，便于单测（不要在 composable 里算）。
需要的 DAO / 仓库改动（描述，不实现）：
- **必须（若走方案 A 的日详情/标记）**：无 schema 改动；复用现有 `getPaymentRecordsForExpense`、`markAsPaid/markAsUnpaid`、`getRecurringExpenseById`。
- **建议（性能）**：`RecurringExpenseDao` 加 `@Query("SELECT * FROM payment_records WHERE paymentDate >= :fromEpoch AND paymentDate < :toEpoch") suspend fun getPaymentRecordsInRange(fromEpoch: Long, toEpoch: Long): List<PaymentRecordEntry>`；`IExpenseRepository` 加 `suspend fun getPaymentRecordsInRange(fromEpoch: Long, toEpoch: Long): Map<Int, Set<Long>>`（一次查询替代 N 次）。同步 3 个假实现（见 2.5）。
- **可选（SQL 预过滤，不改变语义）**：`@Query("SELECT * FROM recurring_expenses WHERE archivedDate IS NULL")` 已有，无需新增；若要按 `endDate` 过滤，可在 Kotlin 侧用 `getNextPaymentDayAfter` 自然处理，不建议加 SQL 日期条件（会与 clamp/时区语义重复）。
- **仅当要"跳过某次/暂停"功能**：新表 `skipped_occurrences(id, expenseId, occurrenceDate)` + `migration_12_13` + DAO + repo + `RecurringExpenseEntry` 无关（新表）；并且 **`UpcomingPaymentsExpander`（3 个 expand 函数）也要查该表**，否则日历与 Upcoming/小组件/通知不一致。
- 若加任何 Dao/Repository 方法：`FakeRecurringExpenseDao`（`ExpenseRepositoryReminderTest:23`）与 4 个 `IExpenseRepository` 实现必须同步，否则 `:shared:jvmTest` 编译失败。

## 8. 测试计划（纯日期逻辑）

新增 `shared/src/commonTest/kotlin/.../model/CalendarMonthProjectionTest.kt`（`kotlin.test` + `runTest`，不依赖 Compose）：
- 月首偏移：2024-01（周一）/2024-09（周日）/闰年 2 月/31 天月，`firstDayOfWeekIso = 1/7` 两种。
- occurrence 枚举：monthly 每月 1 次；`everyXRecurrence=3` 跨月；weekly `everyX=2`；daily `everyX=1` 在 31 天月出 31 条；yearly。
- 月末 clamp 漂移：`firstPayment = 2024-01-31`，断言 1 月 31 / 2 月 29 / 3 月 29（与 `getNextPaymentDayAfter` 一致，not 3 月 31）。
- `endDate`：等于 occurrence 当日 → 含；前一天 → 无；月中截断只出前半段。
- `firstPayment == null` → 空；`archivedDate != null` → 不出现（若投影函数自行过滤，需测）。
- manual 的 paid/unpaid：用 `FakeExpenseRepository.markAsPaid` + UTC epoch 断言 isPaid；跨月后 paid 不泄漏。
- 同日多笔：分组顺序稳定（建议 `compareBy({ date }, { name })`）。
- `groupBy` + 空格子数量 = 月天数 + 前导空格。
运行：`JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew :shared:jvmTest --tests "*CalendarMonthProjectionTest*"`；全量再跑一次防回归。**不要**为 Compose UI 写 instrumented 测试（项目无该基建）。

## 9. 构建 / 测试命令（本机实测，2026-10-24）

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./gradlew :app:assembleDebug            # BUILD SUCCESSFUL（配置缓存可用）
./gradlew :app:assembleRelease          # BUILD SUCCESSFUL（无 SIGNING_STORE_PASSWORD 时用 debug 签名；R8 + lintVital）
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew :shared:jvmTest   # BUILD SUCCESSFUL，126 tests
```
- **必须**给 jvmTest 加 `-Djava.awt.headless=true`：否则 `UpcomingPaymentsViewModelTest` 28 个用例因 AWT 初始化失败（`NoClassDefFoundError ... X11GraphicsEnvironment`）。`DISPLAY` 无需清除（实测 `DISPLAY=:0` + headless 通过）。
- `--no-configuration-cache` **非必需**（`:shared:jvmTest` 与 `:app:assembleDebug` 均在配置缓存开启下通过）；排查缓存问题时再加。
- Java 21（`openjdk 21.0.13`）已就绪，无需 `JAVA_HOME` 覆盖；`local.properties` 已指向 `.toolchain/android-sdk`。
- 偶发 Kotlin daemon 警告 `FileSystemException ... kotlin-daemon-client-tsmarker...tmp: 只读文件系统`，不影响结果；若变致命可加 `-Dkotlin.compiler.execution.strategy=in-process`。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/RecurringExpenseTracker_0.21.4.apk`。

## 10. 坑（按命中概率排序）

1. **commonMain 无 java.time**：用 kotlinx-datetime 0.8.0 + `kotlin.time.Instant/Clock`。`LocalDate.monthNumber` 已 deprecated → `month.number`（需 `import kotlinx.datetime.number`）；`kotlinx.datetime.Instant` 是遗留别名，新代码用 `kotlin.time.Instant`。
2. **UTC vs 本地时区**：`firstPayment`/`endDate`/`payment_records.paymentDate` 全部是 UTC 0 点 epoch；occurrence 是 `LocalDate`（无时区）。`getCurrentLocalDate()`/UI "today" 用系统时区。日历里把 occurrence 转 epoch 必须 `atStartOfDayIn(TimeZone.UTC)`，否则"已付"标记与 Upcoming 差一天。
3. **月首星期无公共 API**：kotlinx-datetime 只有 `LocalDate.dayOfWeek`（`kotlinx.datetime.DayOfWeek`）与 `DayOfWeek.isoDayNumber`；周首日/星期名需自写（7 个字符串 key 最省事，或 expect/actual）。仓库现无任何相关代码可抄。
4. **monthly clamp 漂移**：`getDayOfNextOccurrence` 从 `firstPayment` 逐次 `plus`（`DateTimeCalculator.kt:41-45`），1/31 → 2/29 → 3/29，不是 3/31。日历必须复用同一函数（或同一迭代顺序），否则与 Upcoming 不一致。
5. **`getDayOfNextOccurrence` 是线性循环**：daily 多年 = 上千次迭代；按天 × 按 expense 反复调用会明显卡。VM 里每选中月算一次并缓存（参考 patch 840508d 的 `remember(expense, today)` 思路），不要放在 composable 每次重组里。
6. **语义是"含当日"**：`getNextPaymentDayAfter(afterDate)` 可能返回 `afterDate` 本身；`endDate` 当日仍算（`RecurringExpenseDataTest:14-23`）。日历 cell 判断不要写成 `<`。
7. **没有 paused/skip**：只有 `archivedDate` 与 `endDate`；`endDate` 过期后仅靠通知轮询自动归档（`NotificationLoopReceiver.kt:69`），不是实时。日历要明确是否为 archived/expired 画灰/隐藏。
8. **Room 3（`androidx.room3`）**：实体改动 → `@Database(version=13)` + `migration_12_13` 注册进 builder + 提交 KSP 生成的 `shared/schemas/.../13.json`；漏迁移会导致恢复 v11/v12 备份后崩溃（备份是原始 SQLite 文件，恢复后重启进程跑迁移）。降级由 `fallbackToDestructiveMigrationOnDowngrade(true)` 兜底（仅降级）。
9. **接口扩散**：给 `RecurringExpenseDao`/`IExpenseRepository` 加方法会打断 4 个仓库实现 + 测试内 `FakeRecurringExpenseDao`；能用默认实现/扩展函数就别改接口（本仓库接口无默认方法，历史做法是全量补齐）。
10. **DI 双注册**：新 VM 要同时进 `sharedModule`（运行）与 `previewModule`（@Preview）；`previewModule` 目前缺 `UpcomingPaymentsViewModel`，别重复这个坑。带参 VM 用 `parametersOf` + `viewModel { (param) -> ... }`。
11. **顶栏是共享可变状态**：`MainNavigationViewModel.topAppBar` 是 lambda state；新 destination 必须设置（否则残留上一屏顶栏）；用 `setTopAppBar` 的屏要在调用后再赋值一次（`MainContent.kt:436-442,499-505,513-520`）。
12. **MainContent 有 4 个调用点**：`MainActivity.kt:304`、`shared/src/jvmMain/.../Main.kt:35`、`shared/src/iosMain/.../MainViewController.kt:32`、`MainContent.kt:555`（preview）。新增必填参数要同步 4 处（或给默认值）。
13. **底栏/FAB 可见性是硬编码 route 列表**（`MainContent.kt:171-177,179`）：新 tab 不加进去就没有底栏/FAB。
14. **`DefaultTab` 持久化是 int**（KSafe `DEFAULT_TAB`，`KSafeUserPreferencesRepository.kt:75`）：只能追加新值，不能改旧值/顺序；`fromInt` 遇未知值会抛异常。
15. **Compose Resources 生成**：字符串只加在 `values/strings.xml`（base）+ 需要的 `values-zh-rCN`；访问器由 Gradle 生成（IDE 需 sync），包名固定 `recurringexpensetracker.shared.generated.resources`；复数用 `pluralStringResource`；`translatable="false"` 给品牌名；缺失翻译自动回退 base。
16. **不要用 `LocalDate.toString()` 显示月份/日期**：项目统一走 `toMonthYearStringUTC()` / `Instant.toLocaleString()`（expect/actual）。
17. **Material3 DatePicker 需 `@OptIn(ExperimentalMaterial3Api::class)`**，返回的是 UTC 0 点 millis（`FirstPaymentOption.kt:51,121-128`），与 `firstPayment` 存储语义一致；不要自行做本地时区换算。
18. **状态风格**：跟现有 VM 一样用 snapshot state（`mutableStateOf`/`mutableStateListOf`），频繁刷新时原地更新列表，别换成 `StateFlow` + 全量替换而不给 Lazy item 稳定 key（会丢滚动位置）。
19. **`gridMode` 是全局偏好**（`IUserPreferencesRepository:17`，由 Home/Upcoming 顶栏切换）：日历若复用它，语义会变成"日历也用网格"；建议日历用自己内部的视图模式 state，别污染全局偏好。
20. **其它消费者**：桌面小组件（`UpcomingPaymentsWidgetModel` → `collectFuturePayments`）与通知（`ExpenseNotificationManager` → `getNextPaymentDay`）共用同一日期语义/时区约定；改 recurrence 逻辑必须同时回归它们。

## 11. 关键 file:line 速查

| 主题 | 位置 |
|---|---|
| occurrence 核心 | `shared/.../model/DateTimeCalculator.kt:15,19,27,35,49`；`shared/.../data/RecurringExpenseData.kt:47,60,75` |
| 按月展开 | `shared/.../model/UpcomingPaymentsExpander.kt:16,52,96,148` |
| Upcoming 数据构造 | `shared/.../viewmodel/UpcomingPaymentsViewModel.kt:124,162,325,377` |
| 实体/映射 | `shared/.../model/database/RecurringExpenseEntry.kt:8-55`；`RecurringExpenseWithTagsMapper.kt:9-38`；`RecurringExpenseEntryMapper.kt:6-30` |
| DB/迁移 | `shared/.../model/database/RecurringExpenseDatabase.kt:19,27,34-50,344-351` |
| DAO | `shared/.../model/database/RecurringExpenseDao.kt:15,19,31,34,40,45,106-122` |
| 仓库 | `shared/.../model/database/IExpenseRepository.kt:7-53`；`ExpenseRepository.kt:18-35,46-95,149-174` |
| Tilla 卡片/配色 | `shared/.../ui/TillaExpenseCard.kt:53-74,77-109,118,218-243,292` |
| Home/Upcoming/导航 | `shared/.../ui/MainContent.kt:111-199,200,394,423,444,507,522`；`BottomNavBar.kt:34-62`；`data/Navigation.kt:15-43`；`ui/DefaultTab.kt:3-11` |
| Android 装配 | `app/.../MainActivity.kt:103,162-179,201,280,304-351,358-379`；`ExpenseTrackerApplication.kt:24-31` |
| DI | `shared/.../di/Module.kt:33-66` |
| 格式化 expect/actual | `shared/.../Extensions.kt:17-44`；`Extensions.android.kt:60-75`；`Extensions.ios.kt:39-60` |
| 字符串 | `shared/src/commonMain/composeResources/values/strings.xml`；`values-zh-rCN/strings.xml` |
| 备份 | `app/.../model/DatabaseBackupRestore.kt:18-89` |
| 既有测试范式 | `shared/src/commonTest/.../model/DateTimeCalculatorTest.kt`；`.../data/RecurringExpenseDataTest.kt`；`.../viewmodel/UpcomingPaymentsViewModelTest.kt`（`:28-107` 测试脚手架） |

# 设计文档索引

本目录收录 `apps/*/patches/` 补丁队列所依据的设计资料，供后续维护补丁时回溯“为什么这样实现”。[`tilla-calendar.md`](./tilla-calendar.md) 是对 Tilla 2.8 APK 的逆向设计规格：从导航、布局、尺寸/颜色表、日格状态优先级、手势阈值到数据模型与 `commonMain` 实现计划，是 expenses 补丁 `0011`–`0015`（月历网格、状态持有者、日历 tab、后台投影、底部栏可见性）的直接依据；[`expenses-architecture.md`](./expenses-architecture.md) 则是 RecurringExpenseTracker `feat/tilla-ui` 分支的架构图谱，梳理模块/文件索引、Room 数据层、重复与到期日语义、UI 导航插入点、日期投影数据流、测试计划与构建命令。两份文档都是写作时刻的快照（行号基于当时的 HEAD），上游代码演进后请以仓库为准，仅把这里当作设计与取舍的参考。

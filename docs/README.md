# Docs

Design material behind the patch queues. Both documents were written against a
specific upstream snapshot — treat them as design rationale, not as current
line-by-line truth. The documents themselves are in Simplified Chinese.

- [`design/tilla-calendar.md`](design/tilla-calendar.md) — reverse-engineered
  Tilla 2.8 calendar specification (layout, sizes, day-cell states, gestures,
  data model) that the Expenses calendar patches implement.
- [`design/expenses-architecture.md`](design/expenses-architecture.md) —
  architecture map of RecurringExpenseTracker (`feat/tilla-ui`): modules, Room
  layer, recurrence semantics, UI insertion points, date projection.

Project overview: [English README](../README.md) ·
[简体中文](../README.zh-CN.md).

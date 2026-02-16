# Race Condition Detector — IntelliJ IDEA Plugin

Плагин для статического анализа Java/Kotlin кода, выявляющий потенциальные race conditions при доступе к полям класса из нескольких потоков. Включает специализированную поддержку Folia (региональная модель потоков Minecraft).

## Архитектура (5 модулей)

1. **Field Access Analyzer** — обход PSI-дерева, сбор мест чтения/записи полей и их потокового контекста
2. **Thread Context Resolver** — определение потокового контекста участка кода (MainThread, WorkerThread, Folia-контексты и т.д.)
3. **Synchronization Checker** — проверка наличия синхронизации (volatile, synchronized, Lock, AtomicXxx, @GuardedBy)
4. **Folia Context Analyzer** — определение Folia-проекта по classpath, маппинг scheduler API и событий на потоковые контексты, cross-context проверки
5. **Inspections & Quick-fixes** — инспекции для IDE и автоматические исправления

## Технические соглашения

- Язык плагина: **Kotlin**
- Анализ кода: **UAST** (не голый PSI) для единообразной поддержки Java + Kotlin
- Тесты: **JUnit 4 + `LightJavaCodeInsightFixtureTestCase`** (168 тестов)
- Инспекции наследуют **`AbstractBaseUastLocalInspectionTool`**, регистрируются в `plugin.xml` как `<localInspection>`
- Build: **Gradle + IntelliJ Platform Gradle Plugin 2.x**
- Platform: **IntelliJ IDEA 2024.3+** (platformVersion 243+, compatible up to 253.*)

## Структура пакетов

```
com.racedetector.analysis      — Field Access Analyzer
com.racedetector.threading     — Thread Context Resolver
com.racedetector.sync          — Synchronization Checker
com.racedetector.folia         — Folia Context Analyzer
com.racedetector.inspections   — Inspection-классы
com.racedetector.quickfixes    — Quick-fix классы
```

## Folia-контексты

| Контекст | Описание |
|----------|----------|
| `FoliaGlobalThread` | GlobalRegionScheduler — один глобальный тик |
| `FoliaEntityThread(E)` | EntityScheduler — привязан к конкретной entity |
| `FoliaRegionThread(L)` | RegionScheduler — привязан к конкретной Location |
| `FoliaAsyncThread` | AsyncScheduler — общий пул |

Два `FoliaEntityThread` с разными entity — **разные потоки**. `FoliaEntityThread` и `FoliaRegionThread` — **разные потоки**, если нет доказательства нахождения entity в данном регионе.

## Severity

- **ERROR** — гарантированная гонка
- **WARNING** — вероятная гонка
- **WEAK WARNING** — подозрительный паттерн
- **INFO** — рекомендация

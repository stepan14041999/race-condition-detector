# Плагин для IntelliJ IDEA: Race Condition Detector

## Цель

Разработать плагин для IntelliJ IDEA, который выполняет статический анализ Java/Kotlin кода и выявляет потенциальные гонки потоков (race conditions) при доступе к полям класса из нескольких потоков.

---

## Проблема

В многопоточном коде часто встречаются ситуации, когда несколько потоков одновременно читают и модифицируют одно и то же поле класса без надлежащей синхронизации. Такие ошибки трудно обнаружить при тестировании, поскольку они проявляются недетерминированно и зависят от планировщика потоков.

---

## Функциональные требования

### FR-1. Обнаружение небезопасных полей

Плагин должен находить поля класса, к которым есть доступ (чтение или запись) из кода, который может исполняться в разных потоках, при этом доступ не защищён синхронизацией.

**Критерии «многопоточного доступа»:**

- Поле читается/записывается внутри метода `run()` класса, реализующего `Runnable` или `Callable`
- Поле читается/записывается внутри лямбды, переданной в `ExecutorService.submit()`, `CompletableFuture.runAsync()`, `CompletableFuture.supplyAsync()` и аналоги
- Поле читается/записывается внутри метода, аннотированного `@Async` (Spring), `@Scheduled`, или кастомными аннотациями (настраиваемый список)
- Поле читается/записывается из `Thread::start` напрямую
- Поле читается/записывается из корутинного контекста (Kotlin `launch`, `async`)

**Критерии «отсутствия защиты»:**

- Поле не является `volatile`
- Поле не является `final` (или `val` в Kotlin)
- Доступ не внутри блока `synchronized` по соответствующему монитору
- Поле не является типом из `java.util.concurrent.atomic.*`
- Класс не аннотирован `@ThreadSafe` / `@Immutable`
- Поле не аннотировано `@GuardedBy`

### FR-2. Обнаружение неатомарных составных операций

Плагин должен выявлять паттерны check-then-act и read-modify-write без синхронизации:

```java
// check-then-act
if (map.containsKey(key)) {
    return map.get(key); // между проверкой и получением может вмешаться другой поток
}

// read-modify-write
counter++;  // не атомарно, даже если volatile

// put-if-absent вручную
if (!map.containsKey(key)) {
    map.put(key, value);
}
```

### FR-3. Обнаружение публикации this до завершения конструктора

```java
class Foo {
    private int x;
    
    public Foo(Listener listener) {
        listener.register(this); // this ещё не полностью сконструирован
        this.x = 42;
    }
}
```

### FR-4. Обнаружение небезопасных коллекций в многопоточном контексте

Плагин должен предупреждать, если `HashMap`, `ArrayList`, `HashSet` и другие небезопасные коллекции используются в многопоточном контексте без обёртки `Collections.synchronizedXxx()` или замены на `ConcurrentHashMap`, `CopyOnWriteArrayList` и т.д.

### FR-5. Обнаружение публикации мутабельного состояния

```java
class Service {
    private List<String> items = new ArrayList<>();
    
    public List<String> getItems() {
        return items; // утечка мутабельной ссылки — внешний код может модифицировать без синхронизации
    }
}
```

### FR-6. Folia: проверки региональной модели потоков

Folia (форк Paper) использует модель, где мир разбит на регионы, и каждый регион тикается в собственном потоке. У каждого игрока и каждой локации есть свой scheduler/executor. Обращение к данным из чужого потока — гонка, даже если код выглядит однопоточным.

**Потоковая модель Folia:**

```
GlobalRegionScheduler    — один глобальный тик, нет привязки к региону
EntityScheduler          — привязан к конкретной entity (игроку/мобу)
RegionScheduler          — привязан к конкретной локации (chunk region)
AsyncScheduler           — общий пул, аналог BukkitScheduler.runTaskAsynchronously
```

#### FR-6.1. Доступ к Entity из чужого потока

Плагин должен обнаруживать обращение к `Player`, `Entity`, `LivingEntity` и их методам/полям из контекста, не принадлежащего этой entity:

```java
// ← ERROR: обращение к player из глобального/регионального контекста
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    player.getHealth();            // гонка — player тикается в другом потоке
    player.getInventory().clear(); // гонка
    player.teleport(location);     // гонка
});

// ← ERROR: обращение к entity из async контекста
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    entity.setCustomName("test"); // гонка
});

// OK: через entity scheduler
player.getScheduler().run(plugin, task -> {
    player.getHealth(); // OK — в потоке этого игрока
}, null);
```

#### FR-6.2. Доступ к Block/Chunk/World из чужого потока

Обращение к блокам и чанкам безопасно только из потока соответствующего региона:

```java
// ← ERROR: доступ к блоку из entity scheduler другого региона
player.getScheduler().run(plugin, task -> {
    Block block = otherLocation.getBlock(); // гонка — otherLocation может быть в другом регионе
    block.setType(Material.STONE);
}, null);

// ← ERROR: итерация по чанку из global scheduler
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    for (Entity e : chunk.getEntities()) { // гонка
        e.remove();
    }
});

// OK: через region scheduler с правильной локацией
Bukkit.getRegionScheduler().run(plugin, location, task -> {
    location.getBlock().setType(Material.STONE); // OK — в потоке этого региона
});
```

#### FR-6.3. Поля плагина, доступные из разных scheduler-контекстов

Типичная ошибка в Folia-плагинах — хранение состояния в полях плагина/listener без учёта того, что обработчики событий могут вызываться в разных потоках:

```java
class MyPlugin extends JavaPlugin {
    private Map<UUID, Integer> playerScores = new HashMap<>(); // ← ERROR

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Вызывается в потоке региона игрока — у разных игроков разные потоки!
        playerScores.put(event.getPlayer().getUniqueId(), 0); // гонка
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerScores.remove(event.getPlayer().getUniqueId()); // гонка
    }
}
```

**Критерий:** если к полю плагина обращаются из `@EventHandler`-методов, а плагин работает на Folia, то каждый вызов может идти в своём потоке (привязанном к entity/региону), и поле должно быть потокобезопасным.

#### FR-6.4. Смешивание scheduler-контекстов

Плагин должен обнаруживать паттерны, где один и тот же объект используется в callbacks разных scheduler-ов:

```java
class ArenaManager {
    private List<Player> participants = new ArrayList<>(); // ← ERROR

    void addPlayer(Player player) {
        // Вызывается из entity scheduler игрока
        participants.add(player);
    }

    void startCountdown() {
        // Вызывается из global scheduler
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (Player p : participants) { // гонка — concurrent modification
                p.sendMessage("Starting..."); // + гонка — p в чужом потоке
            }
        }, 1, 20);
    }
}
```

#### FR-6.5. Детектирование использования устаревшего Bukkit Scheduler API

В Folia `BukkitScheduler.runTask()`, `runTaskLater()`, `runTaskTimer()` и аналоги выбрасывают `UnsupportedOperationException`. Плагин должен предупреждать:

```java
// ← ERROR на Folia: Bukkit scheduler не поддерживается
Bukkit.getScheduler().runTask(plugin, () -> { ... });
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { ... });
Bukkit.getScheduler().runTaskLater(plugin, () -> { ... }, 20L);

// OK: Folia-совместимые альтернативы
Bukkit.getGlobalRegionScheduler().run(plugin, task -> { ... });
Bukkit.getAsyncScheduler().runNow(plugin, task -> { ... });
player.getScheduler().run(plugin, task -> { ... }, null);
```

#### FR-6.6. Проверка событий: в чьём потоке вызывается handler

Folia вызывает обработчики событий в потоке, определяемом типом события. Плагин должен знать маппинг:

| Тип события | Поток выполнения |
|-------------|-----------------|
| `PlayerEvent` | Entity scheduler игрока |
| `BlockEvent` | Region scheduler блока |
| `WorldEvent` | Region scheduler (зависит от события) |
| `ServerEvent` | Global region scheduler |
| `InventoryEvent` | Entity scheduler игрока-инициатора |

Если в обработчике `PlayerEvent` идёт обращение к блоку из другого региона — это гонка. Если в обработчике `BlockEvent` идёт обращение к игроку — нужно проверить, что игрок в том же регионе, иначе гонка.

---

## Уровни серьёзности (Severity)

| Уровень | Описание | Пример |
|---------|----------|--------|
| **ERROR** | Гарантированная гонка | Запись в non-volatile поле из разных потоков без синхронизации |
| **WARNING** | Вероятная гонка | Check-then-act на `ConcurrentHashMap` (может быть намеренным) |
| **WEAK WARNING** | Подозрительный паттерн | Не-volatile поле в классе, реализующем `Runnable`, при неочевидном многопоточном доступе |
| **INFO** | Рекомендация | Использование `synchronized` вместо `ReadWriteLock` при преобладании операций чтения |

---

## Нефункциональные требования

### NFR-1. Производительность

- Инспекция одного файла должна занимать не более 200 мс
- Плагин не должен блокировать EDT (Event Dispatch Thread)
- Тяжёлый анализ (межфайловый) должен выполняться в фоновом режиме через `ProgressManager`
- Кэширование результатов анализа — инвалидация при изменении файла

### NFR-2. Совместимость

- IntelliJ IDEA 2024.1+ (platformVersion 241+)
- Java 8–21 (анализируемый код)
- Kotlin 1.9+ (анализируемый код)
- Поддержка Gradle и Maven проектов

### NFR-3. Конфигурируемость

- Настраиваемый список аннотаций, обозначающих многопоточный контекст
- Настраиваемый список аннотаций, обозначающих потокобезопасность
- Возможность подавления предупреждений через `@SuppressWarnings("RaceCondition")`
- Настройки в Settings → Editor → Inspections

### NFR-4. UX

- Подсветка прямо в редакторе (gutter icon + highlight)
- Информативные сообщения: какое поле, откуда доступ, почему опасно
- Quick-fix предложения где возможно

---

## Архитектура

### Модуль 1: PSI-анализ полей (Field Access Analyzer)

Обходит PSI-дерево и собирает информацию о каждом поле:
- Список мест чтения и записи (PsiReference)
- Контекст каждого доступа: в каком методе, в каком потоке (определяется по окружающему контексту)
- Наличие синхронизации в точке доступа

**Ключевые PSI-классы:**
- `PsiField` — поле класса
- `PsiReferenceExpression` — ссылка на поле
- `PsiSynchronizedStatement` — блок synchronized
- `PsiMethodCallExpression` — вызов метода (для обнаружения submit/execute)
- `PsiLambdaExpression` — лямбда (для обнаружения передачи в executor)

### Модуль 2: Thread Context Resolver

Определяет, в каком потоковом контексте находится данный участок кода:

```
MainThread             — код в main(), @EventHandler (до Folia), EDT-контекст
WorkerThread           — код внутри Runnable.run(), Callable.call(), лямбда в executor
ScheduledThread        — @Scheduled, Timer, ScheduledExecutorService
FoliaGlobalThread      — лямбда в GlobalRegionScheduler
FoliaEntityThread(E)   — лямбда в Entity.getScheduler(), handler PlayerEvent/InventoryEvent
FoliaRegionThread(L)   — лямбда в RegionScheduler с привязкой к Location, handler BlockEvent
FoliaAsyncThread       — лямбда в AsyncScheduler
Unknown                — не удалось определить
```

**Стратегия разрешения (общая):**
1. Проверка непосредственного окружения (Runnable, Callable, лямбда)
2. Проверка аннотаций на методе
3. Проверка цепочки вызовов (callchain analysis) — ограниченная глубина (3–5 уровней)
4. Fallback: `Unknown`

**Стратегия разрешения (Folia):**
1. Определить, является ли проект Folia-плагином (наличие зависимости `dev.folia:folia-api` или `io.papermc.paper:paper-api` с Folia-классами в classpath)
2. Если код внутри лямбды, переданной в `GlobalRegionScheduler` → `FoliaGlobalThread`
3. Если код внутри лямбды, переданной в `entity.getScheduler()` → `FoliaEntityThread(entity)`
4. Если код внутри лямбды, переданной в `RegionScheduler` с аргументом `Location` → `FoliaRegionThread(location)`
5. Если код внутри лямбды, переданной в `AsyncScheduler` → `FoliaAsyncThread`
6. Если код в `@EventHandler` — определить тип события по параметру метода и маппить на соответствующий Folia-контекст
7. Два `FoliaEntityThread` с разными entity считаются **разными потоками**
8. `FoliaEntityThread` и `FoliaRegionThread` считаются **разными потоками**, если нельзя доказать, что entity находится в данном регионе

### Модуль 3: Synchronization Checker

Проверяет наличие синхронизации в точке доступа к полю:
- `synchronized(this)` или `synchronized(lock)`
- `ReentrantLock.lock()` / `unlock()` в try-finally
- `ReadWriteLock`
- `StampedLock`
- Поле является `volatile` / `AtomicXxx`
- Аннотация `@GuardedBy`

### Модуль 4: Folia Context Analyzer

Специализированный модуль для анализа Folia-проектов:

**Определение Folia-проекта:**
- Проверка наличия `io.papermc.paper.threadedregions.RegionizedServer` или `io.papermc.paper.threadedregions.RegionizedData` в classpath проекта
- Проверка зависимости `dev.folia:folia-api` в `build.gradle` / `pom.xml`
- Кэширование результата на уровне проекта

**Folia Scheduler Resolver:**
- Распознаёт вызовы `Bukkit.getGlobalRegionScheduler()`, `Bukkit.getRegionScheduler()`, `Bukkit.getAsyncScheduler()`, `entity.getScheduler()`
- Для каждой лямбды/Runnable определяет, в какой scheduler она передана
- Отслеживает аргумент `Location` в `RegionScheduler.run(plugin, location, ...)` для привязки к региону

**Event Thread Mapper:**
- Маппит иерархию типов событий на потоковые контексты
- `PlayerEvent` и его подклассы → `FoliaEntityThread`
- `BlockEvent` и его подклассы → `FoliaRegionThread`
- Конфигурируемый маппинг для кастомных событий

**Cross-Context Checker:**
- Для каждого вызова метода на Entity/Block/World проверяет, совпадает ли потоковый контекст
- `player.method()` внутри `FoliaGlobalThread` → ERROR
- `block.method()` внутри `FoliaEntityThread` → WARNING (может быть тот же регион)
- `entity.method()` внутри `FoliaAsyncThread` → ERROR

### Модуль 5: Inspection & Quick-fixes

**Inspection-класс:**
- Наследуется от `AbstractBaseJavaLocalInspectionTool`
- Регистрируется в `plugin.xml` как `<localInspection>`
- Возвращает `ProblemDescriptor` с нужным severity

**Quick-fix предложения:**
- Добавить `volatile`
- Обернуть в `AtomicReference` / `AtomicInteger` / etc.
- Обернуть в `synchronized` блок
- Заменить коллекцию на concurrent-аналог
- Добавить аннотацию `@GuardedBy`
- Обернуть возвращаемое значение в `Collections.unmodifiableList()`

**Quick-fix предложения (Folia):**
- Заменить `Bukkit.getScheduler().runTask()` → `Bukkit.getGlobalRegionScheduler().run()`
- Обернуть доступ к entity в `entity.getScheduler().run()`
- Обернуть доступ к блоку в `Bukkit.getRegionScheduler().run(plugin, location, ...)`
- Заменить `HashMap`/`ArrayList` в полях плагина на `ConcurrentHashMap`/`CopyOnWriteArrayList`
- Предложить rescheduling паттерн (вынести обращение к entity/block в правильный scheduler)

---

## Этапы реализации

### Этап 1: MVP — Базовое обнаружение (2–3 недели)

**Scope:**
- Обнаружение non-volatile, non-final полей, которые записываются в одном потоковом контексте и читаются в другом
- Поддержка только явных паттернов: `Runnable`, `Callable`, `Thread`, `ExecutorService.submit()`
- Только Java
- Базовые quick-fix: `volatile`, `AtomicXxx`
- Юнит-тесты на 10–15 типовых паттернов

**Deliverables:**
- Рабочий плагин с одной инспекцией
- `plugin.xml` с регистрацией инспекции
- Тесты через `LightJavaCodeInsightFixtureTestCase`

### Этап 2: Расширенный анализ (2–3 недели)

**Scope:**
- Check-then-act и read-modify-write паттерны
- Небезопасные коллекции в многопоточном контексте
- Публикация this из конструктора
- Публикация мутабельного состояния
- Kotlin-поддержка (корутины, `val`/`var`)
- Расширенный набор quick-fix

### Этап 3: Folia-поддержка (2–3 недели)

**Scope:**
- Определение Folia-проекта по classpath (наличие `io.papermc.paper.threadedregions.*`)
- Folia Thread Context Resolver: маппинг scheduler API → потоковый контекст
- Маппинг типов событий на потоки (PlayerEvent → entity, BlockEvent → region и т.д.)
- Обнаружение cross-context доступа к Entity/Block/Chunk
- Обнаружение использования устаревшего BukkitScheduler API
- Обнаружение небезопасного общего состояния между event handlers
- Quick-fix: замена `HashMap` → `ConcurrentHashMap`, предложение rescheduling через `entity.getScheduler()`
- Тесты: 15–20 Folia-специфичных сценариев

**Deliverables:**
- Отдельная инспекция `FoliaRaceConditionInspection` (активируется только при наличии Folia в classpath)
- Инспекция `DeprecatedBukkitSchedulerInspection` для устаревшего API
- Маппинг событий → потоковый контекст (конфигурируемый)

### Этап 4: Глубокий анализ (3–4 недели)

**Scope:**
- Межфайловый анализ (callchain через границы классов)
- Поддержка Spring-аннотаций (`@Async`, `@Scheduled`, `@Transactional` + multithreading)
- Поддержка Android-аннотаций (`@WorkerThread`, `@MainThread`, `@UiThread`)
- Конфигурируемые аннотации
- Настройки в UI (Settings panel)
- Performance-оптимизация и кэширование

### Этап 5: Полировка и публикация (1–2 недели)

**Scope:**
- Документация пользователя
- Минимизация false positives
- Тестирование на реальных open-source проектах (Guava, Netty, Spring)
- Подготовка к публикации на JetBrains Marketplace
- CI/CD через GitHub Actions

---

## Тестовые сценарии

### Должен обнаружить (True Positives)

```java
// 1. Простая гонка: запись из потока, чтение из main
class Counter {
    private int count = 0; // ← WARNING
    
    void increment() {
        new Thread(() -> count++).start();
    }
    
    int getCount() { return count; }
}

// 2. Check-then-act
class Cache {
    private Map<String, Object> map = new HashMap<>(); // ← WARNING (HashMap в MT)
    
    Object get(String key) {
        if (map.containsKey(key)) { // ← WARNING (check-then-act)
            return map.get(key);
        }
        return null;
    }
}

// 3. This-escape
class Listener {
    private int state; // ← WARNING
    
    Listener(EventBus bus) {
        bus.register(this); // ← WARNING (this-escape)
        state = 42;
    }
}

// 4. Мутабельная публикация
class Config {
    private List<String> values = new ArrayList<>();
    
    public List<String> getValues() { 
        return values; // ← WARNING (мутабельная публикация)
    }
}
```

### НЕ должен срабатывать (True Negatives)

```java
// 1. volatile поле
class SafeCounter {
    private volatile int count = 0; // OK
}

// 2. AtomicInteger
class AtomicCounter {
    private final AtomicInteger count = new AtomicInteger(0); // OK
}

// 3. Synchronized доступ
class SyncCounter {
    private int count = 0;
    
    synchronized void increment() { count++; } // OK
    synchronized int getCount() { return count; } // OK
}

// 4. Immutable
class ImmutableConfig {
    private final List<String> items; // OK (final)
    
    ImmutableConfig(List<String> items) {
        this.items = List.copyOf(items);
    }
}

// 5. @GuardedBy
class GuardedCounter {
    private final Object lock = new Object();
    @GuardedBy("lock")
    private int count = 0; // OK — аннотирован
}

// 6. Однопоточный класс (нет признаков MT)
class SimpleService {
    private int state = 0; // OK — нет MT-контекста
    
    void update() { state++; }
    int getState() { return state; }
}
```

### Folia: должен обнаружить (True Positives)

```java
// 1. Доступ к entity из global scheduler
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    player.getHealth();  // ← ERROR: player в чужом потоке
});

// 2. Общее состояние между event handlers (Folia = разные потоки)
class MyListener implements Listener {
    private Map<UUID, Long> cooldowns = new HashMap<>(); // ← ERROR

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        cooldowns.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }
}

// 3. Block доступ из entity scheduler
player.getScheduler().run(plugin, task -> {
    farAwayLocation.getBlock().setType(Material.AIR); // ← ERROR: другой регион
}, null);

// 4. Итерация по entities из async
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    world.getPlayers().forEach(p -> p.sendMessage("hi")); // ← ERROR
});

// 5. Устаревший Bukkit scheduler API
Bukkit.getScheduler().runTask(plugin, () -> { // ← ERROR: не поддерживается в Folia
    doSomething();
});

// 6. Смешивание контекстов через общий список
class Game {
    private Set<Player> players = new HashSet<>(); // ← ERROR

    void join(Player p) { // вызывается из entity scheduler p
        players.add(p);
    }

    void tick() { // вызывается из global scheduler
        for (Player p : players) { p.sendMessage("tick"); }
    }
}
```

### Folia: НЕ должен срабатывать (True Negatives)

```java
// 1. Entity scheduler — доступ к своему entity
player.getScheduler().run(plugin, task -> {
    player.sendMessage("hello");  // OK — свой поток
    player.getHealth();           // OK
}, null);

// 2. Region scheduler — доступ к блоку в своём регионе
Bukkit.getRegionScheduler().run(plugin, location, task -> {
    location.getBlock().setType(Material.STONE); // OK — свой регион
});

// 3. ConcurrentHashMap в общем состоянии
class MyPlugin extends JavaPlugin {
    private final Map<UUID, Integer> scores = new ConcurrentHashMap<>(); // OK

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        scores.put(e.getPlayer().getUniqueId(), 0); // OK — потокобезопасная коллекция
    }
}

// 4. Перешедулинг из одного контекста в другой
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    // Нужен доступ к player — перешедулим в его поток
    player.getScheduler().run(plugin, playerTask -> {
        player.sendMessage("safe"); // OK — правильный rescheduling
    }, null);
});

// 5. Не-Folia проект (обычный Bukkit/Spigot)
// Если в classpath нет Folia API — все event handlers считаются однопоточными
class BukkitPlugin extends JavaPlugin {
    private Map<UUID, Integer> data = new HashMap<>(); // OK на Bukkit (main thread)
    
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data.put(e.getPlayer().getUniqueId(), 0); // OK — всё в main thread
    }
}
```

---

## Технический стек

| Компонент | Технология |
|-----------|------------|
| Язык плагина | Kotlin |
| Build system | Gradle + IntelliJ Platform Gradle Plugin 2.x |
| Platform SDK | IntelliJ Platform SDK 2024.1+ |
| Тестирование | JUnit 5 + `LightJavaCodeInsightFixtureTestCase` |
| CI/CD | GitHub Actions |
| Анализ кода | PSI API + UAST (для поддержки Java + Kotlin) |
| Folia API | `dev.folia:folia-api` (для резолва типов scheduler-ов и событий) |
| Bukkit/Spigot API | `org.spigotmc:spigot-api` (для резолва Bukkit-типов) |

---

## Ограничения и известные edge cases

1. **Reflection** — доступ к полям через рефлексию не обнаруживается
2. **Native-методы** — JNI-вызовы не анализируются
3. **Динамические прокси** — Spring AOP, CGLIB-прокси могут скрывать многопоточность
4. **Thread pools с единственным потоком** — `Executors.newSingleThreadExecutor()` формально однопоточен, но плагин может ложно сработать
5. **Сериализация** — поля, используемые только для сериализации, могут давать ложные срабатывания
6. **Конструкция happens-before** — не все happens-before отношения (например, через `CountDownLatch`) могут быть обнаружены статически
7. **Folia: динамические регионы** — регионы могут сливаться и разделяться во время работы сервера; статически нельзя гарантировать, что два чанка принадлежат одному региону
8. **Folia: entity teleport** — после телепорта entity может оказаться в другом регионе, что меняет её потоковый контекст; статический анализ не может отследить это
9. **Folia: кастомные scheduler-обёртки** — если плагин оборачивает Folia scheduler в свой utility-класс, плагин может не распознать контекст (решается через настраиваемые аннотации)

---

## Метрики успеха

- **Precision** ≥ 80% — не менее 80% срабатываний являются реальными проблемами
- **Recall** ≥ 60% — обнаружение не менее 60% реальных гонок в тестовом наборе
- **Производительность** — полный анализ проекта из 1000 файлов за < 30 секунд
- **Adoption** — 100+ установок за первый месяц на Marketplace

---

## Ссылки

- [IntelliJ Platform SDK Docs](https://plugins.jetbrains.com/docs/intellij/)
- [UAST — Unified AST](https://plugins.jetbrains.com/docs/intellij/uast.html)
- [Creating Inspections](https://plugins.jetbrains.com/docs/intellij/code-inspections.html)
- [Java Concurrency in Practice](https://jcip.net/) — каноническая книга по теме
- [JSR-305 Annotations](https://jcp.org/en/jsr/detail?id=305) — `@GuardedBy`, `@ThreadSafe`
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
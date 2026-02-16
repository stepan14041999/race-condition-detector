# Race Detector Settings

Панель настроек плагина доступна в **Settings → Editor → Inspections → Race Condition Detector**.

## Настройки

### Thread Annotations

**Custom thread context annotations** — список пользовательских аннотаций, которые обозначают потоковый контекст методов.

- Формат: полное имя аннотации (одна на строку)
- Примеры:
  - `android.support.annotation.WorkerThread`
  - `androidx.annotation.MainThread`
  - `com.myapp.annotations.BackgroundTask`

Аннотации, содержащие в имени `Main`, `Ui` или `UiThread`, распознаются как **MainThread**.
Аннотации, содержащие `Worker` или `Background`, распознаются как **WorkerThread**.

**Значения по умолчанию:**
- `android.support.annotation.WorkerThread`
- `androidx.annotation.WorkerThread`
- `android.support.annotation.MainThread`
- `androidx.annotation.MainThread`
- `android.support.annotation.UiThread`
- `androidx.annotation.UiThread`

---

### Thread Safety Annotations

**Custom thread-safe annotations** — список пользовательских аннотаций, которые обозначают потокобезопасность классов/полей.

- Формат: полное имя аннотации (одна на строку)
- Примеры:
  - `javax.annotation.concurrent.ThreadSafe`
  - `com.myapp.annotations.Immutable`

Поля или классы, помеченные этими аннотациями, не будут генерировать предупреждения о race conditions.

**Значения по умолчанию:**
- `org.springframework.context.annotation.Scope`

---

### Analysis Options

#### Folia support

Режим поддержки Folia (Minecraft региональная модель потоков):

- **Auto-detect** (по умолчанию) — автоматически определяет Folia-проект по наличию классов `io.papermc.paper.threadedregions.*` или зависимостей `dev.folia` в build-файлах
- **Always on** — всегда включает Folia-проверки
- **Always off** — полностью отключает Folia-проверки

#### Call chain analysis depth

Глубина анализа цепочек вызовов для определения потокового контекста (1-10).

- **По умолчанию:** 4
- Большая глубина → более точный анализ, но медленнее
- Меньшая глубина → быстрее, но может пропустить некоторые контексты

---

### Framework-Specific Checks

#### Enable Spring Framework checks

Включает проверки для Spring-специфичных паттернов:

- `@Controller`, `@RestController`, `@Service` — singleton bean с mutable state
- `@Async` — методы, выполняющиеся асинхронно
- `@Scheduled` — методы по расписанию
- `@EventListener(async=true)` — асинхронные обработчики событий

**По умолчанию:** включено

#### Enable Android checks

Включает проверки для Android-специфичных аннотаций:

- `@MainThread`, `@UiThread` — код в главном потоке UI
- `@WorkerThread` — код в фоновых потоках

**По умолчанию:** включено

---

## Пример использования

### Добавление custom аннотации

Если в вашем проекте используется custom аннотация:

```java
package com.myapp;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BackgroundTask {
}
```

Добавьте её в **Custom thread context annotations**:

```
com.myapp.BackgroundTask
```

Теперь плагин будет распознавать методы с `@BackgroundTask` как выполняющиеся в WorkerThread контексте.

### Отключение Spring checks

Если вы не используете Spring Framework, отключите **Enable Spring Framework checks** для повышения производительности.

---

## Применение настроек

Настройки применяются немедленно после нажатия **Apply** или **OK**.
Инспекции автоматически переанализируют код с новыми настройками.

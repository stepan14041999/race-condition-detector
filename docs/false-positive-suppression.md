# False Positive Suppression

Данный документ описывает механизмы подавления ложных срабатываний в Race Condition Detector плагине.

## Реализованные механизмы

### 1. @SuppressWarnings("RaceCondition")

Все инспекции поддерживают аннотацию `@SuppressWarnings("RaceCondition")` для подавления предупреждений.

**Примеры использования:**

```java
// На поле
@SuppressWarnings("RaceCondition")
private int counter;

// На классе
@SuppressWarnings("RaceCondition")
public class MyClass {
    private int counter;
}

// На методе
@SuppressWarnings("RaceCondition")
public void checkThenAct() {
    if (!map.containsKey(key)) {
        map.put(key, value);
    }
}

// Массив значений
@SuppressWarnings({"unchecked", "RaceCondition"})
private List<String> items;

// Универсальное подавление
@SuppressWarnings("ALL")
private volatile int field;
```

### 2. Пропуск полей в test-классах

Поля в тестовых классах не проверяются, т.к. тесты обычно выполняются в контролируемой среде.

**Определение тестового класса:**
- Файл находится в директории `src/test/` или `src/tests/`
- Имя класса заканчивается на `Tests`, `TestCase`
- Имя класса соответствует паттерну `.*Test\d+` (например, `MyTest1`)
- Имя класса начинается с `Test` и длина > 4 (например, `TestSuite`)

**Примечание:** Просто `Test` не считается тестовым классом, чтобы избежать ложных срабатываний.

### 3. Пропуск полей в enum

Поля в enum классах не проверяются, т.к. enum обычно immutable по дизайну.

```java
enum Status {
    ACTIVE, INACTIVE;
    private String description; // Не проверяется
}
```

### 4. Пропуск полей в record

Поля в record классах не проверяются, т.к. record компоненты всегда final.

```java
record Person(String name, int age) {} // Поля не проверяются
```

### 5. Пониженный severity для private полей в private inner классах

Private поля в private внутренних классах получают `WEAK_WARNING` вместо обычного уровня.

```java
class Outer {
    private class Inner {
        private int counter; // WEAK_WARNING при многопоточном доступе
    }
}
```

### 6. Пониженный severity для полей с ограниченным использованием

Поля, которые используются только в конструкторе и одном другом методе, получают `WEAK_WARNING`.

```java
class Test {
    private int field;

    public Test() {
        field = 0; // Использование 1: конструктор
    }

    public void init() {
        field = 1; // Использование 2: один метод
    }
    // Нет других использований - будет WEAK_WARNING
}
```

### 7. Корректный double-checked locking паттерн

Правильно реализованный DCL паттерн не вызывает предупреждений.

```java
class Singleton {
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {                    // Первая проверка
            synchronized (Singleton.class) {
                if (instance == null) {            // Вторая проверка
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**Требования для корректного DCL:**
1. Поле должно быть `volatile`
2. Две вложенные проверки на null
3. Synchronized блок между проверками
4. Присвоение значения внутри synchronized блока

## Затронутые инспекции

Все инспекции поддерживают механизмы подавления:

1. **RaceConditionInspection** - базовая проверка race conditions
2. **ReadModifyWriteInspection** - проверка нон-атомарных операций
3. **UnsafeCollectionInspection** - проверка небезопасных коллекций
4. **SpringControllerSharedStateInspection** - проверка shared state в Spring beans
5. **MutableStatePublicationInspection** - проверка публикации mutable состояния
6. **ThisEscapeInspection** - проверка утечки this из конструктора
7. **CheckThenActInspection** - проверка check-then-act паттернов

## Архитектура

Вся логика подавления находится в классе `FalsePositiveFilter` (`com.racedetector.inspections.FalsePositiveFilter`).

**Основные методы:**

- `shouldSkipField(field: PsiField): Boolean` - полное подавление проверки поля
- `shouldReduceSeverity(field: PsiField): Boolean` - снижение уровня warning
- `hasSuppressWarnings(element: PsiModifierListOwner): Boolean` - проверка аннотации
- `isInTestClass(field: PsiField): Boolean` - проверка тестового класса
- `isInEnum(field: PsiField): Boolean` - проверка enum
- `isInRecord(field: PsiField): Boolean` - проверка record
- `isCorrectDoubleCheckedLocking(field: PsiField): Boolean` - проверка DCL паттерна

## Тестирование

Тесты находятся в:

- `FalsePositiveFilterTest` - юнит-тесты для утилитарного класса
- `FalsePositiveSuppressionTest` - интеграционные тесты для всех инспекций

Запуск тестов:

```bash
./gradlew test --tests "com.racedetector.inspections.FalsePositiveFilterTest"
./gradlew test --tests "com.racedetector.inspections.FalsePositiveSuppressionTest"
```

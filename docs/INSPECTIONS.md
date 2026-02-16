# Inspection Reference

Complete reference for all inspections provided by the Race Condition Detector plugin.

## General Threading Issues

### RaceCondition

**Short Name:** `RaceCondition`
**Default Severity:** WARNING
**Quick Fixes:** Add volatile, Replace with Atomic*, Wrap with synchronized

Detects fields accessed from multiple threads without proper synchronization.

```java
// Bad
private int count;

// Good options:
private volatile int count;
private final AtomicInteger count = new AtomicInteger();
private int count; // with synchronized access
```

See: [RaceCondition.html](../src/main/resources/inspectionDescriptions/RaceCondition.html)

---

### CheckThenAct

**Short Name:** `CheckThenAct`
**Default Severity:** WARNING
**Quick Fixes:** Replace with computeIfAbsent, Replace with putIfAbsent

Detects check-then-act patterns without synchronization.

```java
// Bad
if (map.containsKey(key)) {
    return map.get(key);
}

// Good
return map.computeIfAbsent(key, this::compute);
```

See: [CheckThenAct.html](../src/main/resources/inspectionDescriptions/CheckThenAct.html)

---

### ReadModifyWrite

**Short Name:** `ReadModifyWrite`
**Default Severity:** WARNING/ERROR (context-dependent)
**Quick Fixes:** Replace with atomic operation

Detects non-atomic read-modify-write operations.

```java
// Bad
volatile int count;
count++;  // Not atomic!

// Good
AtomicInteger count = new AtomicInteger();
count.incrementAndGet();
```

See: [ReadModifyWrite.html](../src/main/resources/inspectionDescriptions/ReadModifyWrite.html)

---

### ThisEscape

**Short Name:** `ThisEscape`
**Default Severity:** WARNING
**Quick Fixes:** Extract to factory method

Detects 'this' escape during construction.

```java
// Bad
public EventSource(EventListener listener) {
    listener.onCreated(this);  // 'this' escapes
}

// Good - factory method
private EventSource() {}
public static EventSource create(EventListener listener) {
    EventSource source = new EventSource();
    listener.onCreated(source);
    return source;
}
```

See: [ThisEscape.html](../src/main/resources/inspectionDescriptions/ThisEscape.html)

---

### UnsafeCollection

**Short Name:** `UnsafeCollection`
**Default Severity:** WARNING
**Quick Fixes:** Replace with ConcurrentHashMap, Wrap with synchronizedMap

Detects unsafe collections in multi-threaded contexts.

```java
// Bad
private final Map<String, User> users = new HashMap<>();

// Good options:
private final Map<String, User> users = new ConcurrentHashMap<>();
private final Map<String, User> users = Collections.synchronizedMap(new HashMap<>());
```

See: [UnsafeCollection.html](../src/main/resources/inspectionDescriptions/UnsafeCollection.html)

---

### MutableStatePublication

**Short Name:** `MutableStatePublication`
**Default Severity:** WEAK WARNING
**Quick Fixes:** Wrap with unmodifiable*, Replace with copyOf, Defensive copy

Detects mutable internal state exposed via getters.

```java
// Bad
private final List<Employee> employees = new ArrayList<>();
public List<Employee> getEmployees() {
    return employees;  // Exposes internal state
}

// Good options:
return Collections.unmodifiableList(employees);
return List.copyOf(employees);
return new ArrayList<>(employees);
```

See: [MutableStatePublication.html](../src/main/resources/inspectionDescriptions/MutableStatePublication.html)

---

### SpringControllerSharedState

**Short Name:** `SpringControllerSharedState`
**Default Severity:** WARNING
**Requires:** Spring Framework on classpath

Detects mutable instance fields in singleton Spring beans.

```java
// Bad
@RestController
public class UserController {
    private User currentUser;  // Shared across requests!
}

// Good - use local variables
@RestController
public class UserController {
    @GetMapping("/user/{id}")
    public User getUser(@PathVariable String id) {
        User user = userService.findById(id);  // Local variable
        return user;
    }
}
```

See: [SpringControllerSharedState.html](../src/main/resources/inspectionDescriptions/SpringControllerSharedState.html)

---

## Folia Threading Issues

### FoliaCrossContextAccess

**Short Name:** `FoliaCrossContextAccess`
**Default Severity:** WARNING
**Requires:** Folia/Paper on classpath

Detects cross-context API access in Folia projects.

```java
// Bad
Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
    player.sendMessage("Hello");  // Wrong thread!
});

// Good
player.getScheduler().run(plugin, task -> {
    player.sendMessage("Hello");
}, null);
```

See: [FoliaCrossContextAccess.html](../src/main/resources/inspectionDescriptions/FoliaCrossContextAccess.html)

---

### FoliaSharedState

**Short Name:** `FoliaSharedState`
**Default Severity:** ERROR
**Requires:** Folia/Paper on classpath

Detects unsafe shared state in Folia plugins.

```java
// Bad
public class PlayerTracker implements Listener {
    private int count = 0;  // Shared across region threads
}

// Good
public class PlayerTracker implements Listener {
    private final AtomicInteger count = new AtomicInteger();
}
```

See: [FoliaSharedState.html](../src/main/resources/inspectionDescriptions/FoliaSharedState.html)

---

### DeprecatedBukkitScheduler

**Short Name:** `DeprecatedBukkitScheduler`
**Default Severity:** ERROR
**Requires:** Folia/Paper on classpath

Detects deprecated BukkitScheduler usage in Folia.

```java
// Bad - doesn't work in Folia
Bukkit.getScheduler().runTaskTimer(this, () -> {}, 0L, 20L);

// Good
Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {}, 1L, 20L);
```

See: [DeprecatedBukkitScheduler.html](../src/main/resources/inspectionDescriptions/DeprecatedBukkitScheduler.html)

---

## Suppressing Inspections

Use `@SuppressWarnings` with the inspection's short name:

```java
@SuppressWarnings("RaceCondition")
private int counter;

@SuppressWarnings({"CheckThenAct", "ReadModifyWrite"})
public void complexOperation() {
    // ...
}

// Suppress entire class
@SuppressWarnings("ThreadingIssues")
public class MyClass {
    // ...
}
```

## Configuring Severity

1. **Settings/Preferences** → **Editor** → **Inspections**
2. Navigate to **Threading Issues** or **Folia Threading Issues**
3. Select an inspection
4. Change severity: Error / Warning / Weak Warning / Info / No highlighting

## Plugin Settings

**Settings/Preferences** → **Editor** → **Inspection Settings** → **Race Condition Detector**

Available settings:
- **Enable Spring Framework checks** — Enable/disable Spring-specific inspections
- **Enable Folia checks** — Enable/disable Folia-specific inspections
- **Minimum confidence threshold** — Adjust false positive rate
- **Exclude test classes** — Skip test directories (enabled by default)

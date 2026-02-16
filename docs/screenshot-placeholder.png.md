# Screenshot Placeholder

This file is a placeholder for a screenshot showing the plugin in action.

To replace this:
1. Take a screenshot of IntelliJ IDEA showing the plugin highlighting a threading issue
2. Save it as `screenshot-placeholder.png` in this directory
3. Delete this `.md` file

## Recommended Screenshot Content

Show an example like:

```java
public class Counter {
    private int count;  // <- Highlighted with warning

    public void increment() {
        count++;  // <- Highlighted: "Non-atomic read-modify-write"
    }
}
```

With the quick-fix popup visible showing options like:
- Replace with AtomicInteger
- Add volatile modifier
- Wrap with synchronized

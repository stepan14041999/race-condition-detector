package com.racedetector.inspections

import com.racedetector.BaseInspectionTest

class UnsafeCollectionInspectionTest : BaseInspectionTest() {

    override val inspection = UnsafeCollectionInspection()

    // 1. HashMap accessed from main thread and worker thread — WARNING
    fun testHashMapAccessedFromMultipleThreads() {
        myFixture.configureByText(
            "Service.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            public class Service {
                private Map<String, String> cache = new HashMap<>();

                void load() {
                    new Thread(() -> { cache.put("k", "v"); }).start();
                }

                String get(String key) { return cache.get(key); }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertFalse("Should detect unsafe HashMap", highlights.isEmpty())
        assertTrue(
            "Warning should mention 'cache'",
            highlights.any { it.description?.contains("'cache'") == true }
        )
    }

    // 2. ArrayList accessed from Runnable.run() and normal method — WARNING
    fun testArrayListInRunnable() {
        myFixture.configureByText(
            "Worker.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            public class Worker implements Runnable {
                private List<String> items = new ArrayList<>();

                @Override
                public void run() {
                    items.add("item");
                }

                List<String> getItems() { return items; }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertFalse("Should detect unsafe ArrayList in Runnable", highlights.isEmpty())
    }

    // 3. final ConcurrentHashMap — no warning
    fun testFinalConcurrentHashMapNoWarning() {
        myFixture.addClass(
            """
            package java.util.concurrent;
            public class ConcurrentHashMap<K,V> implements java.util.Map<K,V> {
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
                public int size() { return 0; }
                public boolean isEmpty() { return true; }
                public boolean containsKey(Object key) { return false; }
                public boolean containsValue(Object value) { return false; }
                public V remove(Object key) { return null; }
                public void putAll(java.util.Map<? extends K, ? extends V> m) {}
                public void clear() {}
                public java.util.Set<K> keySet() { return null; }
                public java.util.Collection<V> values() { return null; }
                public java.util.Set<java.util.Map.Entry<K,V>> entrySet() { return null; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "SafeService.java",
            """
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.Map;
            public class SafeService {
                private final Map<String, String> cache = new ConcurrentHashMap<>();

                void load() {
                    new Thread(() -> { cache.put("k", "v"); }).start();
                }

                String get(String key) { return cache.get(key); }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertTrue("final ConcurrentHashMap should NOT trigger warning", highlights.isEmpty())
    }

    // 4. final field initialized with Collections.synchronizedMap — no warning
    fun testFinalSynchronizedMapNoWarning() {
        myFixture.configureByText(
            "SyncMapService.java",
            """
            import java.util.Collections;
            import java.util.HashMap;
            import java.util.Map;
            public class SyncMapService {
                private final Map<String, String> cache = Collections.synchronizedMap(new HashMap<>());

                void load() {
                    new Thread(() -> { cache.put("k", "v"); }).start();
                }

                String get(String key) { return cache.get(key); }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertTrue("final synchronizedMap should NOT trigger warning", highlights.isEmpty())
    }

    // 5. final field initialized with List.of() — no warning
    fun testFinalImmutableListOfNoWarning() {
        myFixture.configureByText(
            "ImmutableService.java",
            """
            import java.util.List;
            public class ImmutableService {
                private final List<String> items = List.of("a", "b");

                void process() {
                    new Thread(() -> { String s = items.get(0); }).start();
                }

                List<String> getItems() { return items; }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertTrue("final List.of() should NOT trigger warning", highlights.isEmpty())
    }

    // 6. All accesses inside synchronized — no warning
    fun testAllAccessesSynchronizedNoWarning() {
        myFixture.configureByText(
            "SyncService.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            public class SyncService {
                private Map<String, String> cache = new HashMap<>();

                void load() {
                    new Thread(() -> {
                        synchronized(this) { cache.put("k", "v"); }
                    }).start();
                }

                synchronized String get(String key) { return cache.get(key); }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertTrue("All-synchronized accesses should NOT trigger warning", highlights.isEmpty())
    }

    // ==================== Kotlin Tests ====================

    // Kotlin TP: HashMap accessed from Thread and main method
    fun testKotlinTP_HashMapFromMultipleThreads() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
                public int size() { return 0; }
                public boolean isEmpty() { return true; }
                public boolean containsKey(Object key) { return false; }
                public boolean containsValue(Object value) { return false; }
                public V remove(Object key) { return null; }
                public void putAll(Map<? extends K, ? extends V> m) {}
                public void clear() {}
                public Set<K> keySet() { return null; }
                public Collection<V> values() { return null; }
                public Set<Map.Entry<K,V>> entrySet() { return null; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinService.kt",
            """
            import java.util.HashMap

            class KotlinService {
                private var cache = HashMap<String, String>()

                fun load() {
                    Thread { cache.put("k", "v") }.start()
                }

                fun get(key: String): String? = cache.get(key)
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertFalse("Should detect unsafe HashMap in Kotlin", highlights.isEmpty())
    }

    // Kotlin TN: val with ConcurrentHashMap — no warning
    fun testKotlinTN_ValConcurrentHashMapNoWarning() {
        myFixture.addClass(
            """
            package java.util.concurrent;
            public class ConcurrentHashMap<K,V> implements java.util.Map<K,V> {
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
                public int size() { return 0; }
                public boolean isEmpty() { return true; }
                public boolean containsKey(Object key) { return false; }
                public boolean containsValue(Object value) { return false; }
                public V remove(Object key) { return null; }
                public void putAll(java.util.Map<? extends K, ? extends V> m) {}
                public void clear() {}
                public java.util.Set<K> keySet() { return null; }
                public java.util.Collection<V> values() { return null; }
                public java.util.Set<java.util.Map.Entry<K,V>> entrySet() { return null; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinSafeService.kt",
            """
            import java.util.concurrent.ConcurrentHashMap

            class KotlinSafeService {
                private val cache = ConcurrentHashMap<String, String>()

                fun load() {
                    Thread { cache.put("k", "v") }.start()
                }

                fun get(key: String): String? = cache.get(key)
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Unsafe collection") == true }
        assertTrue("Kotlin val ConcurrentHashMap should NOT trigger warning", highlights.isEmpty())
    }
}

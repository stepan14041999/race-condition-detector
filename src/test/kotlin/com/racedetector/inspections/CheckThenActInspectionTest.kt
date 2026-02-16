package com.racedetector.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.racedetector.BaseInspectionTest

class CheckThenActInspectionTest : BaseInspectionTest() {

    override val inspection: LocalInspectionTool = CheckThenActInspection()

    // ==================== True Positives ====================

    // TP1: map.containsKey() then map.put()
    fun testTP1_ContainsKeyThenPut() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "CacheService.java",
            """
            import java.util.Map;
            import java.util.HashMap;
            public class CacheService {
                private Map<String, Object> cache = new HashMap<>();

                public void addIfMissing(String key, Object value) {
                    if (!cache.containsKey(key)) {
                        cache.put(key, value);
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertFalse("Should detect check-then-act on containsKey/put", highlights.isEmpty())
        assertTrue(
            "Warning should mention 'containsKey'",
            highlights.any { it.description?.contains("containsKey") == true }
        )
    }

    // TP2: map.get(key) != null then map.get(key)
    fun testTP2_GetNotNullThenGet() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "MapReader.java",
            """
            import java.util.Map;
            import java.util.HashMap;
            public class MapReader {
                private Map<String, String> data = new HashMap<>();

                public String read(String key) {
                    if (data.get(key) != null) {
                        return data.get(key);
                    }
                    return null;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertFalse("Should detect check-then-act on get!=null/get", highlights.isEmpty())
        assertTrue(
            "Warning should mention 'get() != null'",
            highlights.any { it.description?.contains("get() != null") == true }
        )
    }

    // TP3: !collection.contains(x) then collection.add(x)
    fun testTP3_NotContainsThenAdd() {
        myFixture.addClass(
            """
            package java.util;
            public class ArrayList<E> implements List<E> {
                public boolean contains(Object o) { return false; }
                public boolean add(E e) { return false; }
                public E get(int index) { return null; }
                public boolean isEmpty() { return true; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface List<E> extends Collection<E> {
                E get(int index);
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Collection<E> {
                boolean contains(Object o);
                boolean add(E e);
                boolean isEmpty();
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "UniqueList.java",
            """
            import java.util.List;
            import java.util.ArrayList;
            public class UniqueList {
                private List<String> items = new ArrayList<>();

                public void addUnique(String item) {
                    if (!items.contains(item)) {
                        items.add(item);
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertFalse("Should detect check-then-act on !contains/add", highlights.isEmpty())
        assertTrue(
            "Warning should mention '!contains()'",
            highlights.any { it.description?.contains("!contains()") == true }
        )
    }

    // ==================== True Negatives ====================

    // TN1: ConcurrentHashMap — no warning
    fun testTN1_ConcurrentHashMapNoWarning() {
        myFixture.addClass(
            """
            package java.util.concurrent;
            import java.util.Map;
            public class ConcurrentHashMap<K,V> implements ConcurrentMap<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
                public V putIfAbsent(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util.concurrent;
            import java.util.Map;
            public interface ConcurrentMap<K,V> extends Map<K,V> {}
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ConcurrentService.java",
            """
            import java.util.concurrent.ConcurrentHashMap;
            public class ConcurrentService {
                private ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();

                public void addIfMissing(String key, Object value) {
                    if (!map.containsKey(key)) {
                        map.put(key, value);
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertTrue("ConcurrentHashMap should NOT trigger check-then-act warning", highlights.isEmpty())
    }

    // TN2: synchronized block — no warning
    fun testTN2_SynchronizedBlockNoWarning() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "SyncService.java",
            """
            import java.util.Map;
            import java.util.HashMap;
            public class SyncService {
                private final Object lock = new Object();
                private Map<String, Object> map = new HashMap<>();

                public void addIfMissing(String key, Object value) {
                    synchronized (lock) {
                        if (!map.containsKey(key)) {
                            map.put(key, value);
                        }
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertTrue("Synchronized block should NOT trigger check-then-act warning", highlights.isEmpty())
    }

    // TN3: synchronized method — no warning
    fun testTN3_SynchronizedMethodNoWarning() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "SyncMethodService.java",
            """
            import java.util.Map;
            import java.util.HashMap;
            public class SyncMethodService {
                private Map<String, Object> map = new HashMap<>();

                public synchronized void addIfMissing(String key, Object value) {
                    if (!map.containsKey(key)) {
                        map.put(key, value);
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertTrue("Synchronized method should NOT trigger check-then-act warning", highlights.isEmpty())
    }

    // ==================== Kotlin Tests ====================

    // Kotlin TP: map.containsKey() then map.put() in Kotlin
    fun testKotlinTP_ContainsKeyThenPut() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinCache.kt",
            """
            import java.util.HashMap

            class KotlinCache {
                private val cache = HashMap<String, Any>()

                fun addIfMissing(key: String, value: Any) {
                    if (!cache.containsKey(key)) {
                        cache.put(key, value)
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertFalse("Should detect check-then-act in Kotlin", highlights.isEmpty())
    }

    // Kotlin TN: synchronized block — no warning
    fun testKotlinTN_SynchronizedNoWarning() {
        myFixture.addClass(
            """
            package java.util;
            public class HashMap<K,V> implements Map<K,V> {
                public boolean containsKey(Object key) { return false; }
                public V get(Object key) { return null; }
                public V put(K key, V value) { return null; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util;
            public interface Map<K,V> {
                boolean containsKey(Object key);
                V get(Object key);
                V put(K key, V value);
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinSyncCache.kt",
            """
            import java.util.HashMap

            class KotlinSyncCache {
                private val lock = Any()
                private val cache = HashMap<String, Any>()

                fun addIfMissing(key: String, value: Any) {
                    synchronized(lock) {
                        if (!cache.containsKey(key)) {
                            cache.put(key, value)
                        }
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Check-then-act") == true }
        assertTrue("Kotlin synchronized should NOT trigger check-then-act", highlights.isEmpty())
    }
}

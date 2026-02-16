package com.racedetector.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.racedetector.BaseInspectionTest

class MutableStatePublicationInspectionTest : BaseInspectionTest() {

    override val inspection: LocalInspectionTool = MutableStatePublicationInspection()

    // ==================== True Positives ====================

    // TP1: public method returns mutable List field directly
    fun testTP1_PublicMethodReturnsMutableList() {
        myFixture.configureByText(
            "Service.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            public class Service {
                private List<String> items = new ArrayList<>();

                public List<String> getItems() {
                    return items;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertFalse("Should detect mutable state publication via public method", highlights.isEmpty())
        assertTrue(
            "Warning should mention field name 'items'",
            highlights.any { it.description?.contains("'items'") == true }
        )
    }

    // TP2: package-private method returns Map field
    fun testTP2_PackagePrivateMethodReturnsMutableMap() {
        myFixture.configureByText(
            "Cache.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            public class Cache {
                private Map<String, Object> data = new HashMap<>();

                Map<String, Object> getData() {
                    return data;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertFalse("Should detect mutable state publication via package-private method", highlights.isEmpty())
    }

    // ==================== True Negatives ====================

    // TN1: return Collections.unmodifiableList(field) — no warning
    fun testTN1_UnmodifiableReturnNoWarning() {
        myFixture.configureByText(
            "SafeService.java",
            """
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            public class SafeService {
                private List<String> items = new ArrayList<>();

                public List<String> getItems() {
                    return Collections.unmodifiableList(items);
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertTrue("Unmodifiable return should NOT trigger warning", highlights.isEmpty())
    }

    // TN2: defensive copy with new ArrayList<>(field) — no warning
    fun testTN2_DefensiveCopyNoWarning() {
        myFixture.configureByText(
            "DefensiveService.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            public class DefensiveService {
                private List<String> items = new ArrayList<>();

                public List<String> getItems() {
                    return new ArrayList<>(items);
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertTrue("Defensive copy should NOT trigger warning", highlights.isEmpty())
    }

    // TN3: private method returning mutable field — no warning
    fun testTN3_PrivateMethodNoWarning() {
        myFixture.configureByText(
            "InternalService.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            public class InternalService {
                private List<String> items = new ArrayList<>();

                private List<String> getItems() {
                    return items;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertTrue("Private method should NOT trigger warning", highlights.isEmpty())
    }

    // ==================== Kotlin Tests ====================

    // Kotlin TP: public method returns mutable list
    fun testKotlinTP_PublicMethodReturnsMutableList() {
        myFixture.addClass(
            """
            package java.util;
            public class ArrayList<E> implements List<E> {
                public boolean add(E e) { return true; }
                public E get(int index) { return null; }
                public int size() { return 0; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinService.kt",
            """
            class KotlinService {
                private val items = java.util.ArrayList<String>()

                fun getItems(): java.util.List<String> {
                    return items
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertFalse("Should detect mutable state publication in Kotlin", highlights.isEmpty())
    }

    // Kotlin TN: private method returns mutable field — no warning
    fun testKotlinTN_PrivateMethodNoWarning() {
        myFixture.addClass(
            """
            package java.util;
            public class ArrayList<E> implements List<E> {
                public boolean add(E e) { return true; }
                public E get(int index) { return null; }
                public int size() { return 0; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "KotlinInternal.kt",
            """
            class KotlinInternal {
                private val items = java.util.ArrayList<String>()

                private fun getItems(): java.util.List<String> {
                    return items
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Mutable internal collection") == true }
        assertTrue("Kotlin private method should NOT trigger warning", highlights.isEmpty())
    }
}

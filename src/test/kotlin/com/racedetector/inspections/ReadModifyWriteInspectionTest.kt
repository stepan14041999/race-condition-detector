package com.racedetector.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.racedetector.BaseInspectionTest

class ReadModifyWriteInspectionTest : BaseInspectionTest() {

    override val inspection: LocalInspectionTool = ReadModifyWriteInspection()

    // ==================== True Positives ====================

    // TP1: counter++ in Runnable.run() — non-atomic in MT context → ERROR
    fun testTP1_IncrementInRunnable() {
        myFixture.configureByText(
            "Counter.java",
            """
            public class Counter implements Runnable {
                private int count = 0;

                @Override
                public void run() {
                    count++;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertFalse("Should detect non-atomic increment in Runnable", highlights.isEmpty())
        assertTrue(
            "Warning should mention field name 'count'",
            highlights.any { it.description?.contains("count") == true }
        )
    }

    // TP2: counter += 5 in Runnable.run() — compound assignment → ERROR
    fun testTP2_CompoundAssignmentInRunnable() {
        myFixture.configureByText(
            "Adder.java",
            """
            public class Adder implements Runnable {
                private int total = 0;

                @Override
                public void run() {
                    total += 5;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertFalse("Should detect non-atomic compound assignment", highlights.isEmpty())
        assertTrue(
            "Warning should mention '+='",
            highlights.any { it.description?.contains("+=") == true }
        )
    }

    // TP3: field = field + 1 — read-modify-write via assignment → ERROR
    fun testTP3_ReadModifyWriteAssignment() {
        myFixture.configureByText(
            "Accumulator.java",
            """
            public class Accumulator implements Runnable {
                private int sum = 0;

                @Override
                public void run() {
                    sum = sum + 1;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertFalse("Should detect read-modify-write pattern", highlights.isEmpty())
    }

    // TP4: volatile counter++ — volatile does not make ++ atomic → WARNING
    fun testTP4_VolatileIncrement() {
        myFixture.configureByText(
            "VolatileCounter.java",
            """
            public class VolatileCounter {
                private volatile int count = 0;

                public void increment() {
                    count++;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("volatile") == true }
        assertFalse("Should warn about volatile increment", highlights.isEmpty())
        assertTrue(
            "Warning should mention atomicity",
            highlights.any { it.description?.contains("atomicity") == true }
        )
    }

    // ==================== True Negatives ====================

    // TN1: counter++ inside synchronized block — no warning
    fun testTN1_SynchronizedNoWarning() {
        myFixture.configureByText(
            "SyncCounter.java",
            """
            public class SyncCounter implements Runnable {
                private int count = 0;

                @Override
                public void run() {
                    synchronized (this) {
                        count++;
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertTrue("Synchronized increment should NOT trigger warning", highlights.isEmpty())
    }

    // ==================== Kotlin Tests ====================

    // Kotlin TP: counter++ in Runnable.run() in Kotlin
    fun testKotlinTP_IncrementInRunnable() {
        myFixture.configureByText(
            "KotlinCounter.kt",
            """
            class KotlinCounter : Runnable {
                private var count = 0

                override fun run() {
                    count++
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertFalse("Should detect non-atomic increment in Kotlin Runnable", highlights.isEmpty())
    }

    // Kotlin TN: volatile counter++ should still warn (volatile doesn't guarantee atomicity)
    // but synchronized block should not warn
    fun testKotlinTN_SynchronizedNoWarning() {
        myFixture.configureByText(
            "KotlinSyncCounter.kt",
            """
            class KotlinSyncCounter : Runnable {
                private var count = 0

                override fun run() {
                    synchronized(this) {
                        count++
                    }
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Non-atomic") == true }
        assertTrue("Kotlin synchronized increment should NOT trigger warning", highlights.isEmpty())
    }
}

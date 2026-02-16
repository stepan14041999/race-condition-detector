package com.racedetector.sync

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.racedetector.analysis.SyncProtection
import org.jetbrains.uast.UField
import org.jetbrains.uast.toUElement

class SynchronizationCheckerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()

        myFixture.addClass(
            """
            package java.util.concurrent.atomic;
            public class AtomicInteger extends Number {
                public AtomicInteger(int initialValue) {}
                public int get() { return 0; }
                public void set(int newValue) {}
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package javax.annotation.concurrent;
            import java.lang.annotation.*;
            @Target(ElementType.FIELD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface GuardedBy {
                String value();
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package javax.annotation.concurrent;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface ThreadSafe {}
            """.trimIndent()
        )
    }

    // 1. volatile field → Volatile
    fun testVolatileField() {
        val field = configureAndFindField(
            """
            public class Test {
                volatile int <caret>counter;
            }
            """.trimIndent()
        )
        assertEquals(SyncProtection.Volatile, SynchronizationChecker.getProtection(field))
    }

    // 2. final field → Final
    fun testFinalField() {
        val field = configureAndFindField(
            """
            public class Test {
                final int <caret>value = 42;
            }
            """.trimIndent()
        )
        assertEquals(SyncProtection.Final, SynchronizationChecker.getProtection(field))
    }

    // 3. AtomicInteger field → AtomicWrapper
    fun testAtomicIntegerField() {
        val field = configureAndFindField(
            """
            import java.util.concurrent.atomic.AtomicInteger;
            public class Test {
                AtomicInteger <caret>counter = new AtomicInteger(0);
            }
            """.trimIndent()
        )
        assertEquals(SyncProtection.AtomicWrapper, SynchronizationChecker.getProtection(field))
    }

    // 4. @GuardedBy("lock") field → GuardedByAnnotation
    fun testGuardedByField() {
        val field = configureAndFindField(
            """
            import javax.annotation.concurrent.GuardedBy;
            public class Test {
                private final Object lock = new Object();
                @GuardedBy("lock")
                int <caret>counter;
            }
            """.trimIndent()
        )
        val protection = SynchronizationChecker.getProtection(field)
        assertTrue(protection is SyncProtection.GuardedByAnnotation)
        assertEquals("lock", (protection as SyncProtection.GuardedByAnnotation).guard)
    }

    // 5. Plain int field → None
    fun testPlainField() {
        val field = configureAndFindField(
            """
            public class Test {
                int <caret>counter;
            }
            """.trimIndent()
        )
        assertEquals(SyncProtection.None, SynchronizationChecker.getProtection(field))
    }

    // 6. Access inside synchronized block → true
    fun testAccessInsideSynchronizedBlock() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                int counter;
                private final Object lock = new Object();
                void increment() {
                    synchronized (lock) {
                        <caret>counter++;
                    }
                }
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(SynchronizationChecker.isAccessSynchronized(element))
    }

    // 7. Access in synchronized method → true
    fun testAccessInSynchronizedMethod() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                int counter;
                synchronized void increment() {
                    <caret>counter++;
                }
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(SynchronizationChecker.isAccessSynchronized(element))
    }

    // 8. Access without synchronization → false
    fun testAccessWithoutSynchronization() {
        myFixture.configureByText(
            "Test.java",
            """
            public class Test {
                int counter;
                void increment() {
                    <caret>counter++;
                }
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertFalse(SynchronizationChecker.isAccessSynchronized(element))
    }

    // 9. Kotlin val → Final
    fun testKotlinValField() {
        myFixture.configureByText(
            "Test.kt",
            """
            class Test {
                val <caret>value = 42
            }
            """.trimIndent()
        )
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        // Navigate up to find the UField, then get its javaPsi (PsiField)
        var psi: PsiElement? = element
        while (psi != null) {
            val uElement = psi.toUElement()
            if (uElement is UField) {
                val field = uElement.javaPsi as PsiField
                assertEquals(SyncProtection.Final, SynchronizationChecker.getProtection(field))
                return
            }
            psi = psi.parent
        }
        fail("Could not find UField at caret position")
    }

    private fun configureAndFindField(code: String): PsiField {
        myFixture.configureByText("Test.java", code)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val field = element.parent as? PsiField
            ?: error("Caret must be placed on a field name. Found: ${element.parent.javaClass.simpleName}")
        return field
    }
}

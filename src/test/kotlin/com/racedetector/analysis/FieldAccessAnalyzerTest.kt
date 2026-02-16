package com.racedetector.analysis

import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FieldAccessAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

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
    }

    // 1. Field written in Thread and read in ordinary method → race
    fun testWriteInThreadReadInMethod_IsRace() {
        val psiClass = configureClass(
            """
            public class Test {
                int counter;

                void read() {
                    int x = counter;
                }

                void writeInThread() {
                    new Thread(() -> {
                        counter = 42;
                    }).start();
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        assertEquals(1, results.size)
        val result = results[0]
        assertEquals("counter", result.field.name)
        assertTrue("Expected race condition", result.isRace)
    }

    // 2. volatile field written from Thread → not race
    fun testVolatileField_NotRace() {
        val psiClass = configureClass(
            """
            public class Test {
                volatile int counter;

                void read() {
                    int x = counter;
                }

                void writeInThread() {
                    new Thread(() -> {
                        counter = 42;
                    }).start();
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        assertEquals(1, results.size)
        assertFalse("Volatile field should not be a race", results[0].isRace)
    }

    // 3. final field → not race
    fun testFinalField_NotRace() {
        val psiClass = configureClass(
            """
            public class Test {
                final int value = 42;

                void read() {
                    int x = value;
                }

                void readInThread() {
                    new Thread(() -> {
                        int y = value;
                    }).start();
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        assertEquals(1, results.size)
        assertFalse("Final field should not be a race", results[0].isRace)
    }

    // 4. AtomicInteger → not race
    fun testAtomicIntegerField_NotRace() {
        val psiClass = configureClass(
            """
            import java.util.concurrent.atomic.AtomicInteger;
            public class Test {
                AtomicInteger counter = new AtomicInteger(0);

                void read() {
                    counter.get();
                }

                void writeInThread() {
                    new Thread(() -> {
                        counter.set(1);
                    }).start();
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        val counterResult = results.find { it.field.name == "counter" }
        assertNotNull("Should find counter field", counterResult)
        assertFalse("AtomicInteger field should not be a race", counterResult!!.isRace)
    }

    // 5. Field used only in one thread context → not race
    fun testSingleThreadContext_NotRace() {
        val psiClass = configureClass(
            """
            public class Test {
                int counter;

                void method1() {
                    counter = 1;
                }

                void method2() {
                    int x = counter;
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        assertEquals(1, results.size)
        assertFalse("Single thread context should not be a race", results[0].isRace)
    }

    // 6. Field only read from different threads → not race
    fun testReadOnlyFromDifferentThreads_NotRace() {
        val psiClass = configureClass(
            """
            public class Test {
                int counter;

                void read() {
                    int x = counter;
                }

                void readInThread() {
                    new Thread(() -> {
                        int y = counter;
                    }).start();
                }
            }
            """.trimIndent()
        )

        val results = FieldAccessAnalyzer.analyze(psiClass)
        assertEquals(1, results.size)
        assertFalse("Read-only from different threads should not be a race", results[0].isRace)
    }

    private fun configureClass(code: String): PsiClass {
        val psiFile = myFixture.configureByText("Test.java", code)
        return PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
            ?: error("No PsiClass found in the configured file")
    }
}

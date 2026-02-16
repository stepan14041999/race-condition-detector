package com.racedetector.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.racedetector.BaseInspectionTest

class ThisEscapeInspectionTest : BaseInspectionTest() {

    override val inspection: LocalInspectionTool = ThisEscapeInspection()

    // ==================== True Positives ====================

    // TP1: this passed as argument to external method and to another constructor
    fun testTP1_ThisPassedAsArgument() {
        myFixture.configureByText(
            "EventSource.java",
            """
            public class EventSource {
                public EventSource(Listener listener) {
                    listener.register(this);
                }

                public interface Listener {
                    void register(EventSource source);
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("'this' passed as argument before constructor completes") == true }
        assertFalse("Should detect 'this' escape via method argument in constructor", highlights.isEmpty())
    }

    // TP2: overridable method called in constructor
    fun testTP2_OverridableMethodCall() {
        myFixture.configureByText(
            "Base.java",
            """
            public class Base {
                private int value;

                public Base() {
                    init();
                }

                protected void init() {
                    value = 42;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Overridable method") == true }
        assertFalse("Should detect overridable method call in constructor", highlights.isEmpty())
        assertTrue(
            "Warning should mention method name 'init'",
            highlights.any { it.description?.contains("'init()'") == true }
        )
    }

    // ==================== True Negatives ====================

    // TN1: final class — overridable method call is safe
    fun testTN1_FinalClassNoWarning() {
        myFixture.configureByText(
            "FinalService.java",
            """
            public final class FinalService {
                private int value;

                public FinalService() {
                    init();
                }

                protected void init() {
                    value = 42;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter {
                it.description?.contains("Overridable method") == true ||
                it.description?.contains("'this'") == true
            }
        assertTrue("Final class should NOT trigger this-escape warnings", highlights.isEmpty())
    }

    // TN2: this passed to private method of same class — safe
    fun testTN2_PrivateMethodCallNoWarning() {
        myFixture.configureByText(
            "SafeInit.java",
            """
            public class SafeInit {
                private int value;

                public SafeInit() {
                    setup(this);
                }

                private void setup(SafeInit instance) {
                    instance.value = 10;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter {
                it.description?.contains("'this' passed as argument") == true
            }
        assertTrue("Passing 'this' to private method of same class should NOT trigger warning", highlights.isEmpty())
    }

    // ==================== Kotlin Tests ====================

    // Kotlin TP: overridable method called in init block (constructor)
    fun testKotlinTP_OverridableMethodInInit() {
        myFixture.configureByText(
            "KotlinBase.kt",
            """
            open class KotlinBase {
                private var value = 0

                init {
                    setup()
                }

                protected open fun setup() {
                    value = 42
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.description?.contains("Overridable method") == true }
        assertFalse("Should detect overridable method call in Kotlin init block", highlights.isEmpty())
    }

    // Kotlin TN: final class (no open modifier) — safe
    fun testKotlinTN_FinalClassNoWarning() {
        myFixture.configureByText(
            "KotlinFinal.kt",
            """
            class KotlinFinal {
                private var value = 0

                init {
                    setup()
                }

                fun setup() {
                    value = 42
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter {
                it.description?.contains("Overridable method") == true ||
                it.description?.contains("'this'") == true
            }
        assertTrue("Non-open Kotlin class should NOT trigger this-escape warnings", highlights.isEmpty())
    }
}

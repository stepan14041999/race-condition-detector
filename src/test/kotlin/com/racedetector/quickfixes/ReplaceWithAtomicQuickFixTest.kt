package com.racedetector.quickfixes

import com.racedetector.BaseInspectionTest
import com.racedetector.inspections.RaceConditionInspection

class ReplaceWithAtomicQuickFixTest : BaseInspectionTest() {

    override val inspection = RaceConditionInspection()

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package java.util.concurrent.atomic;
            public class AtomicInteger extends Number {
                public AtomicInteger() {}
                public AtomicInteger(int initialValue) {}
                public int get() { return 0; }
                public void set(int newValue) {}
                public int incrementAndGet() { return 0; }
            }
            """.trimIndent()
        )
        myFixture.addClass(
            """
            package java.util.concurrent.atomic;
            public class AtomicBoolean implements java.io.Serializable {
                public AtomicBoolean() {}
                public AtomicBoolean(boolean initialValue) {}
                public boolean get() { return false; }
                public void set(boolean newValue) {}
            }
            """.trimIndent()
        )
    }

    fun testReplacesIntWithAtomicInteger() {
        myFixture.configureByText(
            "Counter.java",
            """
            public class Counter {
                private int <caret>count = 0;

                void increment() {
                    new Thread(() -> count++).start();
                }

                int getCount() { return count; }
            }
            """.trimIndent()
        )

        val intention = myFixture.getAvailableIntention("Replace with AtomicInteger")
        assertNotNull("Quick-fix 'Replace with AtomicInteger' should be available", intention)
        myFixture.launchAction(intention!!)

        val text = myFixture.editor.document.text
        assertTrue("Field type should be AtomicInteger", text.contains("AtomicInteger count"))
        assertTrue("Import should be added", text.contains("import java.util.concurrent.atomic.AtomicInteger"))
    }

    fun testAvailableForBooleanField() {
        myFixture.configureByText(
            "Flag.java",
            """
            public class Flag {
                private boolean <caret>active = false;

                void activate() {
                    new Thread(() -> { active = true; }).start();
                }

                boolean isActive() { return active; }
            }
            """.trimIndent()
        )

        val intention = myFixture.getAvailableIntention("Replace with AtomicBoolean")
        assertNotNull(
            "Quick-fix 'Replace with AtomicBoolean' should be available for boolean field",
            intention
        )
    }
}

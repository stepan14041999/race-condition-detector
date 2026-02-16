package com.racedetector.quickfixes

import com.racedetector.BaseInspectionTest
import com.racedetector.inspections.RaceConditionInspection

class WrapWithSynchronizedQuickFixTest : BaseInspectionTest() {

    override val inspection = RaceConditionInspection()

    fun testWrapsAccessesInSynchronized() {
        myFixture.configureByText(
            "Counter.java",
            """
            public class Counter {
                private int <caret>count = 0;

                void increment() {
                    new Thread(() -> { count++; }).start();
                }

                int getCount() {
                    return count;
                }
            }
            """.trimIndent()
        )

        val intention = myFixture.getAvailableIntention(WrapWithSynchronizedQuickFix.NAME)
        assertNotNull("Quick-fix '${WrapWithSynchronizedQuickFix.NAME}' should be available", intention)
        myFixture.launchAction(intention!!)

        val text = myFixture.file.text
        assertTrue(
            "Accesses should be wrapped in synchronized blocks, but got:\n$text",
            text.contains("synchronized")
        )
    }

    fun testAvailableForRaceCondition() {
        myFixture.configureByText(
            "Worker.java",
            """
            public class Worker {
                private int <caret>data;

                void process() {
                    new Thread(() -> { data = 42; }).start();
                }

                int getData() { return data; }
            }
            """.trimIndent()
        )

        val intention = myFixture.getAvailableIntention(WrapWithSynchronizedQuickFix.NAME)
        assertNotNull(
            "Quick-fix '${WrapWithSynchronizedQuickFix.NAME}' should be available for race condition",
            intention
        )
    }
}

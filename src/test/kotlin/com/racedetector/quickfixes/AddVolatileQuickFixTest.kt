package com.racedetector.quickfixes

import com.racedetector.BaseInspectionTest
import com.racedetector.inspections.RaceConditionInspection

class AddVolatileQuickFixTest : BaseInspectionTest() {

    override val inspection = RaceConditionInspection()

    fun testAppliesCorrectly() {
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

        val intention = myFixture.getAvailableIntention(AddVolatileQuickFix.NAME)
        assertNotNull("Quick-fix '${AddVolatileQuickFix.NAME}' should be available", intention)
        myFixture.launchAction(intention!!)

        val text = myFixture.file.text
        assertTrue(
            "Field should have volatile modifier, but got:\n$text",
            text.contains("private volatile int count")
        )
    }

    fun testAvailableForRaceCondition() {
        myFixture.configureByText(
            "Processor.java",
            """
            public class Processor {
                private String <caret>result;

                void processAsync() {
                    new Thread(() -> { result = "done"; }).start();
                }

                String getResult() { return result; }
            }
            """.trimIndent()
        )

        val intention = myFixture.getAvailableIntention(AddVolatileQuickFix.NAME)
        assertNotNull(
            "Quick-fix '${AddVolatileQuickFix.NAME}' should be available for race condition on reference type",
            intention
        )
    }
}

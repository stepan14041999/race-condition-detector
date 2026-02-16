package com.racedetector

import com.racedetector.inspections.RaceConditionInspection

class RaceConditionInspectionSmokeTest : BaseInspectionTest() {

    override val inspection = RaceConditionInspection()

    fun testEmptyFile() {
        doTestNoWarnings("Empty.java")
    }
}

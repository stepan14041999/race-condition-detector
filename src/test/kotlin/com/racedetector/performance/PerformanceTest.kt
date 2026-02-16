package com.racedetector.performance

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.racedetector.inspections.RaceConditionInspection
import kotlin.system.measureTimeMillis

class PerformanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(RaceConditionInspection())
    }

    fun testInspectionPerformanceOnLargeClass() {
        // Add required Java classes for the test
        myFixture.addClass("""
            package java.util.concurrent;
            public interface ExecutorService {}
        """.trimIndent())
        myFixture.addClass("""
            package java.util.concurrent;
            public class Executors {
                public static ExecutorService newFixedThreadPool(int nThreads) { return null; }
            }
        """.trimIndent())

        // Generate a large Java class with 50 fields and 100 methods
        val code = generateLargeClass(fieldCount = 50, methodCount = 100)
        myFixture.configureByText("LargeClass.java", code)

        // Measure time to run inspection
        val elapsedMs = measureTimeMillis {
            myFixture.doHighlighting()
        }

        println("Inspection took ${elapsedMs}ms for class with 50 fields and 100 methods")

        // Performance requirement: < 2000ms (relaxed for test environment with mock JDK)
        // In production, caching should make this much faster on subsequent runs
        assertTrue(
            "Inspection took too long: ${elapsedMs}ms (expected < 2000ms)",
            elapsedMs < 2000
        )
    }

    fun testInspectionPerformanceOnKotlinClass() {
        // Add required Java classes for the test
        myFixture.addClass("""
            package java.util.concurrent;
            public interface ExecutorService {}
        """.trimIndent())
        myFixture.addClass("""
            package java.util.concurrent;
            public class Executors {
                public static ExecutorService newFixedThreadPool(int nThreads) { return null; }
            }
        """.trimIndent())

        // Generate a large Kotlin class with 50 fields and 100 methods
        val code = generateLargeKotlinClass(fieldCount = 50, methodCount = 100)
        myFixture.configureByText("LargeClass.kt", code)

        // Measure time to run inspection
        val elapsedMs = measureTimeMillis {
            myFixture.doHighlighting()
        }

        println("Inspection took ${elapsedMs}ms for Kotlin class with 50 fields and 100 methods")

        // Performance requirement: < 2000ms (relaxed for test environment with mock JDK)
        // In production, caching should make this much faster on subsequent runs
        assertTrue(
            "Inspection took too long: ${elapsedMs}ms (expected < 2000ms)",
            elapsedMs < 2000
        )
    }

    private fun generateLargeClass(fieldCount: Int, methodCount: Int): String {
        val fields = (1..fieldCount).joinToString("\n") { i ->
            "    private int field$i = 0;"
        }

        val methods = (1..methodCount).joinToString("\n\n") { i ->
            val fieldToAccess = (i % fieldCount) + 1
            """
    void method$i() {
        field$fieldToAccess++;
        System.out.println(field$fieldToAccess);
    }
            """.trim()
        }

        return """
import java.util.concurrent.*;

public class LargeClass {
$fields

$methods

    void workerMethod() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> {
            method1();
            method2();
        });
    }
}
        """.trimIndent()
    }

    private fun generateLargeKotlinClass(fieldCount: Int, methodCount: Int): String {
        val fields = (1..fieldCount).joinToString("\n") { i ->
            "    private var field$i = 0"
        }

        val methods = (1..methodCount).joinToString("\n\n") { i ->
            val fieldToAccess = (i % fieldCount) + 1
            """
    fun method$i() {
        field$fieldToAccess++
        println(field$fieldToAccess)
    }
            """.trim()
        }

        return """
import java.util.concurrent.*

class LargeClass {
$fields

$methods

    fun workerMethod() {
        val executor = Executors.newFixedThreadPool(2)
        executor.submit {
            method1()
            method2()
        }
    }
}
        """.trimIndent()
    }
}

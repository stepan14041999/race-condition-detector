package com.racedetector.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class SpringControllerSharedStateInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(SpringControllerSharedStateInspection())
        addSpringMocks()
    }

    /**
     * Test 1: Mutable non-volatile field in @RestController → WARNING
     */
    fun testMutableFieldInRestController() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.web.bind.annotation.GetMapping;
            import java.util.ArrayList;

            @RestController
            public class Test {
                private int counter = 0;
                private ArrayList<String> items = new ArrayList<>();

                @GetMapping("/count")
                public int getCount() {
                    return counter;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .filter { it.description?.contains("singleton Spring bean") == true }
        assertEquals(
            "Expected 2 warnings (counter + items), got: ${highlights.joinToString { "'${it.description}'" }}",
            2,
            highlights.size
        )
    }

    /**
     * Test 2: Final field in @Controller → no warning
     */
    fun testFinalFieldInControllerIsOk() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.GetMapping;

            @Controller
            public class Test {
                private final String name = "hello";

                @GetMapping("/name")
                public String getName() {
                    return name;
                }
            }
            """.trimIndent()
        )
        assertNoSpringSharedStateWarnings()
    }

    /**
     * Test 3: Mutable field in @Service (singleton by default) → WARNING
     */
    fun testMutableFieldInService() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.stereotype.Service;

            @Service
            public class Test {
                private int requestCount = 0;

                public void handle() {
                    requestCount++;
                }
            }
            """.trimIndent()
        )
        val highlights = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .filter { it.description?.contains("singleton Spring bean") == true }
        assertEquals(1, highlights.size)
        assertTrue(highlights[0].description!!.contains("requestCount"))
    }

    /**
     * Test 4: @Scope("prototype") bean → no warning (not singleton)
     */
    fun testPrototypeScopeBeanIsOk() {
        myFixture.configureByText(
            "Test.java",
            """
            import org.springframework.stereotype.Controller;
            import org.springframework.context.annotation.Scope;

            @Controller
            @Scope("prototype")
            public class Test {
                private int counter = 0;

                public void handle() {
                    counter++;
                }
            }
            """.trimIndent()
        )
        assertNoSpringSharedStateWarnings()
    }

    // ==================== Helpers ====================

    private fun assertNoSpringSharedStateWarnings() {
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= HighlightSeverity.WARNING.myVal }
            .filter { it.description?.contains("singleton Spring bean") == true }
        assertTrue(
            "Expected no Spring shared state warnings, but found: " +
                highlights.joinToString { "'${it.description}'" },
            highlights.isEmpty()
        )
    }

    // ==================== Mock classes ====================

    private fun addSpringMocks() {
        myFixture.addClass(
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Controller {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface RestController {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Service {}
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.springframework.context.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Scope {
                String value() default "singleton";
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package org.springframework.web.bind.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface GetMapping {
                String value() default "";
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package java.util;
            public class ArrayList<E> {
                public boolean add(E e) { return true; }
            }
            """.trimIndent()
        )
    }
}

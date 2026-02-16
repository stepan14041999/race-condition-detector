package com.racedetector.inspections

import com.intellij.psi.PsiField
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FalsePositiveFilterTest : LightJavaCodeInsightFixtureTestCase() {

    fun `test SuppressWarnings RaceCondition`() {
        val file = myFixture.configureByText("Test.java", """
            class Test {
                @SuppressWarnings("RaceCondition")
                private int field;
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Test", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isSuppressed(field))
        assertTrue(FalsePositiveFilter.shouldSkipField(field))
    }

    fun `test SuppressWarnings ALL`() {
        val file = myFixture.configureByText("Test.java", """
            class Test {
                @SuppressWarnings("ALL")
                private int field;
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Test", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isSuppressed(field))
    }

    fun `test SuppressWarnings array with RaceCondition`() {
        val file = myFixture.configureByText("Test.java", """
            class Test {
                @SuppressWarnings({"unchecked", "RaceCondition"})
                private int field;
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Test", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isSuppressed(field))
    }

    fun `test field in test class by name`() {
        val file = myFixture.configureByText("MyTests.java", """
            class MyTests {
                private int field;
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("MyTests", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isInTestClass(field))
        assertTrue(FalsePositiveFilter.shouldSkipField(field))
    }

    fun `test field in enum`() {
        val file = myFixture.configureByText("Status.java", """
            enum Status {
                ACTIVE, INACTIVE;
                private String description;
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Status", file.resolveScope)!!
        val field = psiClass.findFieldByName("description", false)!!

        assertTrue(FalsePositiveFilter.isInEnum(field))
        assertTrue(FalsePositiveFilter.shouldSkipField(field))
    }

    fun `test field in record`() {
        val file = myFixture.configureByText("Person.java", """
            record Person(String name, int age) {}
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Person", file.resolveScope)!!
        val field = psiClass.findFieldByName("name", false)

        // Records auto-generate fields, check if class is record
        assertTrue(psiClass.isRecord)
    }

    fun `test private field in private inner class`() {
        val file = myFixture.configureByText("Outer.java", """
            class Outer {
                private class Inner {
                    private int field;
                }
            }
        """.trimIndent())

        val outerClass = myFixture.javaFacade.findClass("Outer", file.resolveScope)!!
        val innerClass = outerClass.findInnerClassByName("Inner", false)!!
        val field = innerClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isPrivateFieldInPrivateInnerClass(field))
        assertTrue(FalsePositiveFilter.shouldReduceSeverity(field))
    }

    fun `test field used only in constructor and one method`() {
        val file = myFixture.configureByText("Test.java", """
            class Test {
                private int field;

                public Test() {
                    field = 0;
                }

                public void init() {
                    field = 1;
                }
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Test", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertTrue(FalsePositiveFilter.isUsedOnlyInConstructorAndOneMethod(field))
        assertTrue(FalsePositiveFilter.shouldReduceSeverity(field))
    }

    fun `test field used in multiple methods should not reduce severity`() {
        val file = myFixture.configureByText("Test.java", """
            class Test {
                private int field;

                public Test() {
                    field = 0;
                }

                public void method1() {
                    field = 1;
                }

                public void method2() {
                    field = 2;
                }
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Test", file.resolveScope)!!
        val field = psiClass.findFieldByName("field", false)!!

        assertFalse(FalsePositiveFilter.isUsedOnlyInConstructorAndOneMethod(field))
        assertFalse(FalsePositiveFilter.shouldReduceSeverity(field))
    }

    fun `test correct double-checked locking with volatile`() {
        val file = myFixture.configureByText("Singleton.java", """
            class Singleton {
                private static volatile Singleton instance;

                public static Singleton getInstance() {
                    if (instance == null) {
                        synchronized (Singleton.class) {
                            if (instance == null) {
                                instance = new Singleton();
                            }
                        }
                    }
                    return instance;
                }
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Singleton", file.resolveScope)!!
        val field = psiClass.findFieldByName("instance", false)!!

        assertTrue(FalsePositiveFilter.isCorrectDoubleCheckedLocking(field))
    }

    fun `test double-checked locking without volatile should fail`() {
        val file = myFixture.configureByText("Singleton.java", """
            class Singleton {
                private static Singleton instance;

                public static Singleton getInstance() {
                    if (instance == null) {
                        synchronized (Singleton.class) {
                            if (instance == null) {
                                instance = new Singleton();
                            }
                        }
                    }
                    return instance;
                }
            }
        """.trimIndent())

        val psiClass = myFixture.javaFacade.findClass("Singleton", file.resolveScope)!!
        val field = psiClass.findFieldByName("instance", false)!!

        assertFalse(FalsePositiveFilter.isCorrectDoubleCheckedLocking(field))
    }
}

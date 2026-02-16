package com.racedetector

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

abstract class BaseInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    protected abstract val inspection: LocalInspectionTool

    override fun getTestDataPath(): String = "src/test/testData"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(inspection)
    }

    /**
     * Load a file from testData/ and check highlighting.
     * The file may contain <warning> / <weak_warning> markers for expected diagnostics.
     */
    protected fun doTest(fileName: String) {
        myFixture.configureByFile(fileName)
        myFixture.checkHighlighting(true, false, true)
    }

    /**
     * Load a file from testData/ and verify the inspection produces no warnings.
     */
    protected fun doTestNoWarnings(fileName: String) {
        myFixture.configureByFile(fileName)
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WARNING.myVal }
        assertTrue(
            "Expected no warnings, but found ${highlights.size}: " +
                highlights.joinToString { "'${it.description}' at offset ${it.startOffset}" },
            highlights.isEmpty()
        )
    }

    /**
     * Check that the inspection reports at least one warning at the given 1-based [line].
     * Optionally match warning text against [messageFragment].
     */
    protected fun assertHasWarningAtLine(line: Int, messageFragment: String? = null) {
        val doc = myFixture.editor.document
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WARNING.myVal }

        val found = highlights.any { h ->
            val hLine = doc.getLineNumber(h.startOffset) + 1
            hLine == line && (messageFragment == null || h.description?.contains(messageFragment) == true)
        }
        assertTrue(
            "Expected warning at line $line" +
                (messageFragment?.let { " containing '$it'" } ?: "") +
                ", but got warnings: " +
                highlights.joinToString {
                    "'${it.description}' at line ${doc.getLineNumber(it.startOffset) + 1}"
                }.ifEmpty { "none" },
            found
        )
    }

    /**
     * Configure from inline text and check highlighting (file uses annotation markers).
     */
    protected fun doTestFromText(javaCode: String, fileName: String = "Test.java") {
        myFixture.configureByText(fileName, javaCode)
        myFixture.checkHighlighting(true, false, true)
    }

    /**
     * Configure from inline text and verify no warnings are produced.
     */
    protected fun doTestNoWarningsFromText(javaCode: String, fileName: String = "Test.java") {
        myFixture.configureByText(fileName, javaCode)
        val highlights = myFixture.doHighlighting()
            .filter { it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WARNING.myVal }
        assertTrue(
            "Expected no warnings, but found ${highlights.size}: " +
                highlights.joinToString { "'${it.description}' at offset ${it.startOffset}" },
            highlights.isEmpty()
        )
    }
}

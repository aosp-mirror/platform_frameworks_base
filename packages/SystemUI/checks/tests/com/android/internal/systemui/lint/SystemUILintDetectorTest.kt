package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import java.io.File
import org.junit.ClassRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.model.Statement

@RunWith(JUnit4::class)
abstract class SystemUILintDetectorTest : LintDetectorTest() {

    companion object {
        @ClassRule @JvmField val libraryChecker: LibraryExists = LibraryExists(*libraryNames)
    }

    class LibraryExists(vararg val libraryNames: String) : TestRule {
        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    for (libName in libraryNames) {
                        val libFile = File(libName)
                        if (!libFile.canonicalFile.exists()) {
                            throw Exception(
                                "Could not find $libName in the test's working directory. " +
                                    "File ${libFile.absolutePath} does not exist."
                            )
                        }
                    }
                    base.evaluate()
                }
            }
        }
    }
    /**
     * Customize the lint task to disable SDK usage completely. This ensures that running the tests
     * in Android Studio has the same result as running the tests in atest
     */
    override fun lint(): TestLintTask =
        super.lint().allowMissingSdk(true).sdkHome(File("/dev/null"))
}

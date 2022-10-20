package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import java.io.File

@Suppress("UnstableApiUsage")
abstract class SystemUILintDetectorTest : LintDetectorTest() {
    /**
     * Customize the lint task to disable SDK usage completely. This ensures that running the tests
     * in Android Studio has the same result as running the tests in atest
     */
    override fun lint(): TestLintTask =
        super.lint().allowMissingSdk(true).sdkHome(File("/dev/null"))
}

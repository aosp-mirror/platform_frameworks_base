/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class TestFunctionNameViolationDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = TestFunctionNameViolationDetector()

    override fun getIssues(): List<Issue> = listOf(TestFunctionNameViolationDetector.ISSUE)

    @Test
    fun violations() {
        lint()
            .files(
                TestFiles.kotlin(
                        """
                    package test.pkg.name

                    import org.junit.Test

                    class MyTest {
                        @Test
                        fun `illegal test name - violation should be detected`() {
                            // some test code here.
                        }

                        @Test
                        fun legitimateTestName_doesNotViolate() {
                            // some test code here.
                        }

                        fun helperFunction_doesNotViolate() {
                            // some code.
                        }

                        fun `helper function - does not violate`() {
                            // some code.
                        }
                    }
                """
                    )
                    .indented(),
                testAnnotationStub
            )
            .issues(TestFunctionNameViolationDetector.ISSUE)
            .run()
            .expectWarningCount(0)
            .expect(
                """
                src/test/pkg/name/MyTest.kt:7: Error: Spaces are not allowed in test names. Use pascalCase_withUnderScores instead. [TestFunctionNameViolation]
                    fun `illegal test name - violation should be detected`() {
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
            )
    }

    companion object {
        private val testAnnotationStub: TestFile =
            kotlin(
                """
                package org.junit

                import java.lang.annotation.ElementType
                import java.lang.annotation.Retention
                import java.lang.annotation.RetentionPolicy
                import java.lang.annotation.Target

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.METHOD})
                annotation class Test
            """
            )
    }
}

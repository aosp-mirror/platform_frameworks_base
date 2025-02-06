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
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class RunTestShouldUseKosmosDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = RunTestShouldUseKosmosDetector()

    override fun getIssues(): List<Issue> = listOf(RunTestShouldUseKosmosDetector.ISSUE)

    @Test
    fun wronglyTriesToUseScopeRunTest() {
        val runOnSource =
            runOnSource(
                """
                      package test.pkg.name

                      import com.android.systemui.kosmos.Kosmos
                      import kotlinx.coroutines.test.runTest
                      import kotlinx.coroutines.test.TestScope
                      import org.junit.Test

                      class MyTest {
                          val scope: TestScope
                          val kosmos: Kosmos

                          @Test
                          fun badTest() = scope.runTest {
                              // test code
                          }
                      }
                """
            )

        runOnSource
            .expectWarningCount(1)
            .expect(
                """
                src/test/pkg/name/MyTest.kt:13: Warning: Prefer Kosmos.runTest to TestScope.runTest in sysui tests that use Kosmos.  go/kosmos-runtest [RunTestShouldUseKosmos]
                    fun badTest() = scope.runTest {
                                          ~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testScopeRunTestIsOKifKosmosNotUsed() {
        runOnSource(
                """
                      package test.pkg.name

                      import kotlinx.coroutines.test.runTest
                      import kotlinx.coroutines.test.TestScope
                      import org.junit.Test

                      class MyTest {
                          val scope: TestScope

                          @Test
                          fun okTest() = scope.runTest {
                              // test code
                          }
                      }
                """
            )
            .expectWarningCount(0)
    }

    @Test
    fun otherTestScopeMethodsAreOK() {
        runOnSource(
                """
                       package test.pkg.name

                       import com.android.systemui.kosmos.Kosmos
                       import com.android.systemui.kosmos.runTest
                       import kotlinx.coroutines.test.TestScope
                       import org.junit.Test

                       class MyTest {
                           val scope: TestScope
                           val kosmos: Kosmos

                           @Test
                           fun okTest() = kosmos.runTest {
                               scope.cancel()
                               // test code
                           }
                       }
                   """
            )
            .expectWarningCount(0)
    }

    @Test
    fun correctlyUsesKosmosRunTest() {
        runOnSource(
                """
                       package test.pkg.name

                       import com.android.systemui.kosmos.Kosmos
                       import com.android.systemui.kosmos.runTest
                       import kotlinx.coroutines.test.TestScope
                       import org.junit.Test

                       class MyTest {
                           val scope: TestScope
                           val kosmos: Kosmos

                           @Test
                           fun okTest() = kosmos.runTest {
                               // test code
                           }
                       }
                   """
            )
            .expectWarningCount(0)
    }

    private fun runOnSource(source: String): TestLintResult {
        return lint()
            .files(
                TestFiles.kotlin(source).indented(),
                testAnnotationStub,
                runTestStub,
                testScopeStub,
                kosmosStub,
                kosmosRunTestStub,
            )
            .issues(RunTestShouldUseKosmosDetector.ISSUE)
            .run()
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

        private val runTestStub: TestFile =
            kotlin(
                """
                package kotlinx.coroutines.test

                fun TestScope.runTest(
                    timeout: Duration = DEFAULT_TIMEOUT.getOrThrow(),
                    testBody: suspend TestScope.() -> Unit
                ): Unit = {}
            """
            )

        private val testScopeStub: TestFile =
            kotlin(
                """
                package kotlinx.coroutines.test

                class TestScope

                public fun TestScope.cancel() {}
            """
            )

        private val kosmosStub: TestFile =
            kotlin(
                """
                package com.android.systemui.kosmos

                class Kosmos
            """
            )

        private val kosmosRunTestStub: TestFile =
            kotlin(
                """
                package com.android.systemui.kosmos

                fun Kosmos.runTest(testBody: suspend Kosmos.() -> Unit)
                """
            )
    }
}

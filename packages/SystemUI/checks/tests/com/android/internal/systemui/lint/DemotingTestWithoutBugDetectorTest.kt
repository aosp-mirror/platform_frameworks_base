/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class DemotingTestWithoutBugDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = DemotingTestWithoutBugDetector()
    override fun getIssues(): List<Issue> = listOf(DemotingTestWithoutBugDetector.ISSUE)

    @Test
    fun testMarkFlaky_withBugId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import androidx.test.filters.FlakyTest;

                        @FlakyTest(bugId = 123)
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expectClean()

        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.platform.test.annotations.FlakyTest;

                        @FlakyTest(bugId = 123)
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testMarkFlaky_withoutBugId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import androidx.test.filters.FlakyTest;

                        @FlakyTest
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug id to track demoted test [DemotingTestWithoutBug]
                @FlakyTest
                ~~~~~~~~~~
                0 errors, 1 warnings
                """
            )

        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.platform.test.annotations.FlakyTest;

                        @FlakyTest
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug id to track demoted test [DemotingTestWithoutBug]
                @FlakyTest
                ~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    private val filtersFlakyTestStub: TestFile =
        java(
            """
        package androidx.test.filters;

        public @interface FlakyTest {
            int bugId() default -1;
        }
        """
        )
    private val annotationsFlakyTestStub: TestFile =
        java(
            """
        package android.platform.test.annotations;

        public @interface FlakyTest {
            int bugId() default -1;
        }
        """
        )
    private val stubs = arrayOf(filtersFlakyTestStub, annotationsFlakyTestStub)
}

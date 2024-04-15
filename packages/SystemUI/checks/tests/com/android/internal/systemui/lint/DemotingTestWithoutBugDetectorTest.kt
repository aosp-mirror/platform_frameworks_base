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
import com.android.tools.lint.detector.api.Scope
import java.util.EnumSet
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
            .customScope(testScope)
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
            .customScope(testScope)
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
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug id to track demoted test, e.g. @FlakyTest(bugId = 123) [DemotingTestWithoutBug]
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
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug id to track demoted test, e.g. @FlakyTest(bugId = 123) [DemotingTestWithoutBug]
                @FlakyTest
                ~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testExcludeDevices_withBugId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.platform.test.rule.PlatinumRule.Platinum;

                        @Platinum(devices = "foo,bar", bugId = 123)
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testExcludeDevices_withoutBugId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.platform.test.rule.PlatinumRule.Platinum;

                        @Platinum(devices = "foo,bar")
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug id to track demoted test, e.g. @FlakyTest(bugId = 123) [DemotingTestWithoutBug]
                @Platinum(devices = "foo,bar")
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testIgnore_withBug() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import org.junit.Ignore;

                        @Ignore("Blocked by b/123.")
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testIgnore_withoutBug() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import org.junit.Ignore;

                        @Ignore
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug to track demoted test, e.g. @Ignore("b/123") [DemotingTestWithoutBug]
                @Ignore
                ~~~~~~~
                0 errors, 1 warnings
                """
            )

        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import org.junit.Ignore;

                        @Ignore("Not ready")
                        public class TestClass {
                            public void testCase() {}
                        }
                    """
                    )
                    .indented(),
                *stubs
            )
            .customScope(testScope)
            .issues(DemotingTestWithoutBugDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:4: Warning: Please attach a bug to track demoted test, e.g. @Ignore("b/123") [DemotingTestWithoutBug]
                @Ignore("Not ready")
                ~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    private val testScope = EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
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
    private val annotationsPlatinumStub: TestFile =
        java(
            """
        package android.platform.test.rule;

        public class PlatinumRule {
            public @interface Platinum {
                String devices();
                int bugId() default -1;
            }
        }
        """
        )
    private val annotationsIgnoreStub: TestFile =
        java(
            """
        package org.junit;

        public @interface Ignore {
            String value() default "";
        }
        """
        )
    private val stubs =
        arrayOf(
            filtersFlakyTestStub,
            annotationsFlakyTestStub,
            annotationsPlatinumStub,
            annotationsIgnoreStub
        )
}

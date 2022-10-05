/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

@Suppress("UnstableApiUsage")
class NonInjectedServiceDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = NonInjectedServiceDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)
    override fun getIssues(): List<Issue> = listOf(NonInjectedServiceDetector.ISSUE)

    @Test
    fun testGetServiceWithString() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.content.Context;

                        public class TestClass1 {
                            public void getSystemServiceWithoutDagger(Context context) {
                                context.getSystemService("user");
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(NonInjectedServiceDetector.ISSUE)
            .run()
            .expectWarningCount(1)
            .expectContains("Use @Inject to get the handle")
    }

    @Test
    fun testGetServiceWithClass() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.content.Context;
                        import android.os.UserManager;

                        public class TestClass2 {
                            public void getSystemServiceWithoutDagger(Context context) {
                                context.getSystemService(UserManager.class);
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(NonInjectedServiceDetector.ISSUE)
            .run()
            .expectWarningCount(1)
            .expectContains("Use @Inject to get the handle")
    }

    private val stubs = androidStubs
}

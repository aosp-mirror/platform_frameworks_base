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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

@Suppress("UnstableApiUsage")
class NonInjectedServiceDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = NonInjectedServiceDetector()
    override fun getIssues(): List<Issue> = listOf(NonInjectedServiceDetector.ISSUE)

    @Test
    fun testGetServiceWithString() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.content.Context;

                        public class TestClass {
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
            .expect(
                """
                src/test/pkg/TestClass.java:6: Warning: Use @Inject to get system-level service handles instead of Context.getSystemService() [NonInjectedService]
                        context.getSystemService("user");
                                ~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
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

                        public class TestClass {
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
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Use @Inject to get system-level service handles instead of Context.getSystemService() [NonInjectedService]
                        context.getSystemService(UserManager.class);
                                ~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressGetServiceWithClass() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.content.Context;
                        import android.os.UserManager;

                        public class TestClass {
                            @SuppressLint("NonInjectedService")
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
            .expectClean()
    }

    @Test
    fun testGetAccountManager() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.content.Context;
                        import android.accounts.AccountManager;

                        public class TestClass {
                            public void getSystemServiceWithoutDagger(Context context) {
                                AccountManager.get(context);
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(NonInjectedServiceDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Replace AccountManager.get() with an injected instance of AccountManager [NonInjectedService]
                        AccountManager.get(context);
                                       ~~~
                0 errors, 1 warnings
                """
            )
    }

    private val stubs = androidStubs
}

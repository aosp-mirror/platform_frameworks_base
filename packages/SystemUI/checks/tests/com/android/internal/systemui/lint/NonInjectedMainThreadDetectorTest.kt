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

class NonInjectedMainThreadDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = NonInjectedMainThreadDetector()

    override fun getIssues(): List<Issue> = listOf(NonInjectedMainThreadDetector.ISSUE)

    @Test
    fun testGetMainThreadHandler() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.Handler;

                    public class TestClass {
                        public void test(Context context) {
                          Handler mainThreadHandler = context.getMainThreadHandler();
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(NonInjectedMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Replace with injected @Main Executor. [NonInjectedMainThread]
                      Handler mainThreadHandler = context.getMainThreadHandler();
                                                          ~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressGetMainThreadHandler() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.Handler;

                    @SuppressWarnings("NonInjectedMainThread")
                    public class TestClass {
                        public void test(Context context) {
                          Handler mainThreadHandler = context.getMainThreadHandler();
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(NonInjectedMainThreadDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testGetMainLooper() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.Looper;

                    public class TestClass {
                        public void test(Context context) {
                          Looper mainLooper = context.getMainLooper();
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(NonInjectedMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Replace with injected @Main Executor. [NonInjectedMainThread]
                      Looper mainLooper = context.getMainLooper();
                                                  ~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testGetMainExecutor() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import java.util.concurrent.Executor;

                    public class TestClass {
                        public void test(Context context) {
                          Executor mainExecutor = context.getMainExecutor();
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(NonInjectedMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Replace with injected @Main Executor. [NonInjectedMainThread]
                      Executor mainExecutor = context.getMainExecutor();
                                                      ~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }
}

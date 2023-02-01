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
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class GetMainLooperViaContextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = GetMainLooperViaContextDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> = listOf(GetMainLooperViaContextDetector.ISSUE)

    private val explanation = "Please inject a @Main Executor instead."

    @Test
    fun testGetMainThreadHandler() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.Handler;

                    public class TestClass1 {
                        public void test(Context context) {
                          Handler mainThreadHandler = context.getMainThreadHandler();
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(GetMainLooperViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testGetMainLooper() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.Looper;

                    public class TestClass1 {
                        public void test(Context context) {
                          Looper mainLooper = context.getMainLooper();
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(GetMainLooperViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testGetMainExecutor() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import java.util.concurrent.Executor;

                    public class TestClass1 {
                        public void test(Context context) {
                          Executor mainExecutor = context.getMainExecutor();
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(GetMainLooperViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    private val contextStub: TestFile = java(
            """
        package android.content;
        import android.os.Handler;import android.os.Looper;import java.util.concurrent.Executor;

        public class Context {
            public Looper getMainLooper() { return null; };
            public Executor getMainExecutor() { return null; };
            public Handler getMainThreadHandler() { return null; };
        }
        """
    )

    private val looperStub: TestFile = java(
            """
        package android.os;

        public class Looper {}
        """
    )

    private val handlerStub: TestFile = java(
            """
        package android.os;

        public class Handler {}
        """
    )

    private val stubs = arrayOf(contextStub, looperStub, handlerStub)
}

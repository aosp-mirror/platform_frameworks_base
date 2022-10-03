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

class BindServiceViaContextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = BindServiceViaContextDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> = listOf(
            BindServiceViaContextDetector.ISSUE)

    private val explanation = "Binding or unbinding services are synchronous calls"

    @Test
    fun testBindService() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass1 {
                        public void bind(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.bindService(intent, null, 0);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(BindServiceViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testBindServiceAsUser() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.UserHandle;

                    public class TestClass1 {
                        public void bind(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.bindServiceAsUser(intent, null, 0, UserHandle.ALL);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(BindServiceViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testUnbindService() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.content.ServiceConnection;

                    public class TestClass1 {
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(BindServiceViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    private val contextStub: TestFile = java(
            """
        package android.content;
        import android.os.UserHandle;

        public class Context {
            public void bindService(Intent intent) {};
            public void bindServiceAsUser(Intent intent, ServiceConnection connection, int flags,
                                          UserHandle userHandle) {};
            public void unbindService(ServiceConnection connection) {};
        }
        """
    )

    private val serviceConnectionStub: TestFile = java(
            """
        package android.content;

        public class ServiceConnection {}
        """
    )

    private val userHandleStub: TestFile = java(
            """
        package android.os;

        public enum UserHandle {
            ALL
        }
        """
    )

    private val stubs = arrayOf(contextStub, serviceConnectionStub, userHandleStub)
}

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

class RegisterReceiverViaContextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RegisterReceiverViaContextDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> = listOf(
            RegisterReceiverViaContextDetector.ISSUE)

    private val explanation = "BroadcastReceivers should be registered via BroadcastDispatcher."

    @Test
    fun testRegisterReceiver() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.IntentFilter;

                    public class TestClass1 {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter) {
                          context.registerReceiver(receiver, filter, 0);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(RegisterReceiverViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testRegisterReceiverAsUser() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.IntentFilter;
                    import android.os.Handler;
                    import android.os.UserHandle;

                    public class TestClass1 {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter, Handler handler) {
                          context.registerReceiverAsUser(receiver, UserHandle.ALL, filter,
                            "permission", handler);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(RegisterReceiverViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    @Test
    fun testRegisterReceiverForAllUsers() {
        lint().files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.IntentFilter;
                    import android.os.Handler;
                    import android.os.UserHandle;

                    public class TestClass1 {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter, Handler handler) {
                          context.registerReceiverForAllUsers(receiver, filter, "permission",
                            handler);
                        }
                    }
                """
                ).indented(),
                *stubs)
                .issues(RegisterReceiverViaContextDetector.ISSUE)
                .run()
                .expectWarningCount(1)
                .expectContains(explanation)
    }

    private val contextStub: TestFile = java(
            """
        package android.content;
        import android.os.Handler;
        import android.os.UserHandle;

        public class Context {
            public void registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                int flags) {};
            public void registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {};
            public void registerReceiverForAllUsers(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler) {};
        }
        """
    )

    private val broadcastReceiverStub: TestFile = java(
            """
        package android.content;

        public class BroadcastReceiver {}
        """
    )

    private val intentFilterStub: TestFile = java(
            """
        package android.content;

        public class IntentFilter {}
        """
    )

    private val handlerStub: TestFile = java(
            """
        package android.os;

        public class Handler {}
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

    private val stubs = arrayOf(contextStub, broadcastReceiverStub, intentFilterStub, handlerStub,
            userHandleStub)
}

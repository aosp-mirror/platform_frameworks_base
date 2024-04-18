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

class RegisterReceiverViaContextDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = RegisterReceiverViaContextDetector()

    override fun getIssues(): List<Issue> = listOf(RegisterReceiverViaContextDetector.ISSUE)

    @Test
    fun testRegisterReceiver() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import android.content.Context;

                    public class TestClass {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter) {
                          context.registerReceiver(receiver, filter, 0);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterReceiverViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: Register BroadcastReceiver using BroadcastDispatcher instead of Context [RegisterReceiverViaContext]
                      context.registerReceiver(receiver, filter, 0);
                              ~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressRegisterReceiver() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import android.content.Context;

                    @SuppressWarnings("RegisterReceiverViaContext")
                    public class TestClass {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter) {
                          context.registerReceiver(receiver, filter, 0);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterReceiverViaContextDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testRegisterReceiverAsUser() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import android.content.Context;

                    public class TestClass {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter, Handler handler) {
                          context.registerReceiverAsUser(receiver, UserHandle.ALL, filter,
                            "permission", handler);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterReceiverViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: Register BroadcastReceiver using BroadcastDispatcher instead of Context [RegisterReceiverViaContext]
                      context.registerReceiverAsUser(receiver, UserHandle.ALL, filter,
                              ~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testRegisterReceiverForAllUsers() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import android.content.Context;

                    public class TestClass {
                        public void bind(Context context, BroadcastReceiver receiver,
                            IntentFilter filter, Handler handler) {
                          context.registerReceiverForAllUsers(receiver, filter, "permission",
                            handler);
                        }
                    }
                """
                    )
                    .indented(),
                *androidStubs
            )
            .issues(RegisterReceiverViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: Register BroadcastReceiver using BroadcastDispatcher instead of Context [RegisterReceiverViaContext]
                      context.registerReceiverForAllUsers(receiver, filter, "permission",
                              ~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }
}

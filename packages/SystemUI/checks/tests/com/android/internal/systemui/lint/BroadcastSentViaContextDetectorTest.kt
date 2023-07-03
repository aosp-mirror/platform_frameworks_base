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
class BroadcastSentViaContextDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = BroadcastSentViaContextDetector()

    override fun getIssues(): List<Issue> = listOf(BroadcastSentViaContextDetector.ISSUE)

    @Test
    fun testSendBroadcast() {
        println(stubs.size)
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void send(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.sendBroadcast(intent);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: Context.sendBroadcast() should be replaced with BroadcastSender.sendBroadcast() [BroadcastSentViaContext]
                      context.sendBroadcast(intent);
                              ~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSendBroadcastAsUser() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.UserHandle;

                    public class TestClass {
                        public void send(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: Context.sendBroadcastAsUser() should be replaced with BroadcastSender.sendBroadcastAsUser() [BroadcastSentViaContext]
                      context.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                              ~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSendBroadcastInActivity() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.app.Activity;
                    import android.os.UserHandle;

                    public class TestClass {
                        public void send(Activity activity) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          activity.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                        }

                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: Context.sendBroadcastAsUser() should be replaced with BroadcastSender.sendBroadcastAsUser() [BroadcastSentViaContext]
                      activity.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                               ~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressSendBroadcastInActivity() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.app.Activity;
                    import android.os.UserHandle;

                    public class TestClass {
                        @SuppressWarnings("BroadcastSentViaContext")
                        public void send(Activity activity) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          activity.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                        }

                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testSendBroadcastInBroadcastSender() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package com.android.systemui.broadcast;
                    import android.app.Activity;
                    import android.os.UserHandle;

                    public class BroadcastSender {
                        public void send(Activity activity) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          activity.sendBroadcastAsUser(intent, UserHandle.ALL, "permission");
                        }

                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testNoopIfNoCall() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void sendBroadcast() {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.startActivity(intent);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectClean()
    }

    private val stubs = androidStubs
}

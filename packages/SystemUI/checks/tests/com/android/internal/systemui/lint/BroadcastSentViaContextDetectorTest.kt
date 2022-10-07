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
class BroadcastSentViaContextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = BroadcastSentViaContextDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

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

                    public class TestClass1 {
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
            .expectWarningCount(1)
            .expectContains(
                "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead."
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

                    public class TestClass1 {
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
            .expectWarningCount(1)
            .expectContains(
                "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead."
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

                    public class TestClass1 {
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
            .expectWarningCount(1)
            .expectContains(
                "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead."
            )
    }

    @Test
    fun testNoopIfNoCall() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass1 {
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

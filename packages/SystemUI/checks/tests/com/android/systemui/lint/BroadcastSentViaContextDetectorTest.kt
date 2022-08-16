package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class BroadcastSentViaContextDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = BroadcastSentViaContextDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> = listOf(
        BroadcastSentViaContextDetector.ISSUE)

    @Test
    fun testSendBroadcast() {
        lint().files(
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
                ).indented(),
                *stubs)
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectWarningCount(1)
            .expectContains(
            "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead.")
    }

    @Test
    fun testSendBroadcastAsUser() {
        lint().files(
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
                """).indented(),
                *stubs)
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectWarningCount(1)
            .expectContains(
            "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead.")
    }

    @Test
    fun testSendBroadcastInActivity() {
        lint().files(
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
                """).indented(),
                *stubs)
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectWarningCount(1)
            .expectContains(
            "Please don't call sendBroadcast/sendBroadcastAsUser directly on " +
                    "Context, use com.android.systemui.broadcast.BroadcastSender instead.")
    }

    @Test
    fun testNoopIfNoCall() {
        lint().files(
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
                """).indented(),
                *stubs)
            .issues(BroadcastSentViaContextDetector.ISSUE)
            .run()
            .expectClean()
    }

    private val contextStub: TestFile = java(
        """
        package android.content;
        import android.os.UserHandle;

        public class Context {
            public void sendBroadcast(Intent intent) {};
            public void sendBroadcast(Intent intent, String receiverPermission) {};
            public void sendBroadcastAsUser(Intent intent, UserHandle userHandle,
                                                String permission) {};
        }
        """
    )

    private val activityStub: TestFile = java(
        """
        package android.app;
        import android.content.Context;

        public class Activity extends Context {}
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

    private val stubs = arrayOf(contextStub, activityStub, userHandleStub)
}

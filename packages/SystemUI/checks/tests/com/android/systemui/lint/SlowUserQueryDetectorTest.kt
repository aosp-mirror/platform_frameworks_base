package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class SlowUserQueryDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = SlowUserQueryDetector()
    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    override fun getIssues(): List<Issue> =
        listOf(
            SlowUserQueryDetector.ISSUE_SLOW_USER_ID_QUERY,
            SlowUserQueryDetector.ISSUE_SLOW_USER_INFO_QUERY
        )

    @Test
    fun testGetCurrentUser() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.app.ActivityManager;

                        public class TestClass1 {
                            public void slewlyGetCurrentUser() {
                                ActivityManager.getCurrentUser();
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(
                SlowUserQueryDetector.ISSUE_SLOW_USER_ID_QUERY,
                SlowUserQueryDetector.ISSUE_SLOW_USER_INFO_QUERY
            )
            .run()
            .expectWarningCount(1)
            .expectContains(
                "ActivityManager.getCurrentUser() is slow. " +
                    "Use UserTracker.getUserId() instead."
            )
    }

    @Test
    fun testGetUserInfo() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.os.UserManager;

                        public class TestClass2 {
                            public void slewlyGetUserInfo(UserManager userManager) {
                                userManager.getUserInfo();
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(
                SlowUserQueryDetector.ISSUE_SLOW_USER_ID_QUERY,
                SlowUserQueryDetector.ISSUE_SLOW_USER_INFO_QUERY
            )
            .run()
            .expectWarningCount(1)
            .expectContains(
                "UserManager.getUserInfo() is slow. " + "Use UserTracker.getUserInfo() instead."
            )
    }

    @Test
    fun testUserTrackerGetUserId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import com.android.systemui.settings.UserTracker;

                        public class TestClass3 {
                            public void quicklyGetUserId(UserTracker userTracker) {
                                userTracker.getUserId();
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(
                SlowUserQueryDetector.ISSUE_SLOW_USER_ID_QUERY,
                SlowUserQueryDetector.ISSUE_SLOW_USER_INFO_QUERY
            )
            .run()
            .expectClean()
    }

    @Test
    fun testUserTrackerGetUserInfo() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import com.android.systemui.settings.UserTracker;

                        public class TestClass4 {
                            public void quicklyGetUserId(UserTracker userTracker) {
                                userTracker.getUserInfo();
                            }
                        }
                        """
                    )
                    .indented(),
                *stubs
            )
            .issues(
                SlowUserQueryDetector.ISSUE_SLOW_USER_ID_QUERY,
                SlowUserQueryDetector.ISSUE_SLOW_USER_INFO_QUERY
            )
            .run()
            .expectClean()
    }

    private val activityManagerStub: TestFile =
        java(
            """
            package android.app;

            public class ActivityManager {
                public static int getCurrentUser() {};
            }
            """
        )

    private val userManagerStub: TestFile =
        java(
            """
            package android.os;
            import android.content.pm.UserInfo;
            import android.annotation.UserIdInt;

            public class UserManager {
                public UserInfo getUserInfo(@UserIdInt int userId) {};
            }
            """
        )

    private val userIdIntStub: TestFile =
        java(
            """
            package android.annotation;

            public @interface UserIdInt {}
            """
        )

    private val userInfoStub: TestFile =
        java(
            """
            package android.content.pm;

            public class UserInfo {}
            """
        )

    private val userTrackerStub: TestFile =
        java(
            """
            package com.android.systemui.settings;
            import android.content.pm.UserInfo;

            public interface UserTracker {
                public int getUserId();
                public UserInfo getUserInfo();
            }
            """
        )

    private val stubs =
        arrayOf(activityManagerStub, userManagerStub, userIdIntStub, userInfoStub, userTrackerStub)
}

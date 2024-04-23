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

class SlowUserQueryDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = SlowUserQueryDetector()

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

                        public class TestClass {
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
            .expect(
                """
                src/test/pkg/TestClass.java:6: Warning: Use UserTracker.getUserId() instead of ActivityManager.getCurrentUser() [SlowUserIdQuery]
                        ActivityManager.getCurrentUser();
                                        ~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
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

                        public class TestClass {
                            public void slowlyGetUserInfo(UserManager userManager) {
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
            .expect(
                """
                src/test/pkg/TestClass.java:6: Warning: Use UserTracker.getUserInfo() instead of UserManager.getUserInfo() [SlowUserInfoQuery]
                        userManager.getUserInfo();
                                    ~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressGetUserInfo() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import android.os.UserManager;

                        public class TestClass {
                            @SuppressWarnings("SlowUserInfoQuery")
                            public void slowlyGetUserInfo(UserManager userManager) {
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
            .expectClean()
    }

    @Test
    fun testUserTrackerGetUserId() {
        lint()
            .files(
                TestFiles.java(
                        """
                        package test.pkg;
                        import com.android.systemui.settings.UserTracker;

                        public class TestClass {
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

                        public class TestClass {
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

    private val stubs =
        arrayOf(
            *androidStubs,
            java(
                    """
package com.android.systemui.settings;
import android.content.pm.UserInfo;
public interface UserTracker {
    int getUserId();
    UserInfo getUserInfo();
}
"""
                )
                .indented()
        )
}

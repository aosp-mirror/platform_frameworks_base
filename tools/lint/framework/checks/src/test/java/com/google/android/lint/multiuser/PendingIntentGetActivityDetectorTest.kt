/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.google.android.lint.multiuser

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class PendingIntentGetActivityDetectorTest : LintDetectorTest() {

  override fun getDetector(): Detector = PendingIntentGetActivityDetector()

  override fun getIssues(): List<Issue> =
    listOf(PendingIntentGetActivityDetector.ISSUE_PENDING_INTENT_GET_ACTIVITY)

  override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

  fun testPendingIntentGetActivity() {
    lint()
      .files(
        java(
            """
                package test.pkg;

                import android.app.PendingIntent;
                import android.content.Context;
                import android.content.Intent;

                public class TestClass {
                    private Context mContext;

                    public void testMethod(Intent intent) {
                        PendingIntent.getActivity(
                            mContext, /*requestCode=*/0, intent,
                            PendingIntent.FLAG_IMMUTABLE, /*options=*/null
                        );
                    }
                }
                """
          )
          .indented(),
        *stubs,
      )
      .issues(PendingIntentGetActivityDetector.ISSUE_PENDING_INTENT_GET_ACTIVITY)
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:11: Warning: Using PendingIntent.getActivity(...) might not be multiuser-aware. Consider using the user aware method PendingIntent.getActivityAsUser(...). [PendingIntent#getActivity]
                PendingIntent.getActivity(
                ^
        0 errors, 1 warnings
        """
      )
  }

  fun testPendingIntentGetActivityAsUser() {
    lint()
      .files(
        java(
            """
                package test.pkg;

                import android.app.PendingIntent;
                import android.content.Context;
                import android.content.Intent;
                import android.os.UserHandle;

                public class TestClass {
                    private Context mContext;

                    public void testMethod(Intent intent) {
                        PendingIntent.getActivityAsUser(
                            mContext, /*requestCode=*/0, intent,
                            0, /*options=*/null,
                            UserHandle.CURRENT
                        );
                    }
                }
                """
          )
          .indented(),
        *stubs,
      )
      .issues(PendingIntentGetActivityDetector.ISSUE_PENDING_INTENT_GET_ACTIVITY)
      .run()
      .expectClean()
  }

  private val pendingIntentStub: TestFile =
    java(
      """
        package android.app;

        import android.content.Context;
        import android.content.Intent;
        import android.os.UserHandle;

        public class PendingIntent {
            public static boolean getActivity(Context context, int requestCode, Intent intent, int flags) {
                return true;
            }

            public static boolean getActivityAsUser(
                Context context,
                int requestCode,
                Intent intent,
                int flags,
                UserHandle userHandle
            ) {
                return true;
            }
        }
        """
    )

  private val contxtStub: TestFile =
    java(
      """
        package android.content;

        import android.os.UserHandle;

        public class Context {

           public Context createContextAsUser(UserHandle userHandle, int flags) {
                return this;
            }
        }

        """
    )

  private val userHandleStub: TestFile =
    java(
      """
        package android.os;

        public class UserHandle {

        }

        """
    )

 private val intentStub: TestFile =
    java(
        """
                package android.content;

                public class Intent {

                }
                """
    )

  private val stubs = arrayOf(pendingIntentStub, contxtStub, userHandleStub, intentStub)
}

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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import java.util.EnumSet
import org.jetbrains.uast.UCallExpression

/**
 * Detector for flagging potential multiuser issues in `PendingIntent.getActivity()` calls.
 *
 * This detector checks for calls to `PendingIntent#getActivity()` and
 * reports a warning if such a call is found, suggesting that the
 * default user 0 context might not be the right one.
 */
class PendingIntentGetActivityDetector : Detector(), SourceCodeScanner {

  companion object {

    val description = """Flags potential multiuser issue in PendingIntent.getActivity() calls."""

    val EXPLANATION =
      """
      **Problem:**

      Calling `PendingIntent.getActivity()` in the `system_server` often accidentally uses the user 0 context.  Moreover, since there's no explicit user parameter in the `getActivity` method, it can be hard to tell which user the `PendingIntent` activity is associated with, making the code error-prone and less readable.

      **Solution:**

      Always use the user aware methods to refer the correct user context. You can achieve this by:

      * **Using `PendingIntent.getActivityAsUser(...)`:** This API allows you to explicitly specify the user for the activity.

         ```java
         PendingIntent.getActivityAsUser(
             mContext, /*requestCode=*/0, intent,
             PendingIntent.FLAG_IMMUTABLE, /*options=*/null,
             UserHandle.of(mUserId));
         ```

      **When to Ignore this Warning:**

      You can safely ignore this warning if you are certain that:

      * You've confirmed that the `PendingIntent` activity you're targeting is the correct one and is **rightly** associated with the context parameter passed into the `PendingIntent.getActivity` method.

      **Note:** If you are unsure about the user context, it's best to err on the side of caution and explicitly specify the user using the method specified above.

      **For any further questions, please reach out to go/multiuser-help.**
      """.trimIndent()

    val ISSUE_PENDING_INTENT_GET_ACTIVITY: Issue =
      Issue.create(
        id = "PendingIntent#getActivity",
        briefDescription = description,
        explanation = EXPLANATION,
        category = Category.SECURITY,
        priority = 8,
        severity = Severity.WARNING,
        implementation =
          Implementation(
            PendingIntentGetActivityDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES),
          ),
      )
  }

  override fun getApplicableMethodNames() = listOf("getActivity")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    // Check if the method call is PendingIntent.getActivity
    if (
      context.evaluator.isMemberInClass(method, "android.app.PendingIntent") &&
        method.name == "getActivity"
    ) {
        context.report(
          ISSUE_PENDING_INTENT_GET_ACTIVITY,
          node,
          context.getLocation(node),
          "Using `PendingIntent.getActivity(...)` might not be multiuser-aware. " +
            "Consider using the user aware method `PendingIntent.getActivityAsUser(...)`.",
        )
    }
  }
}

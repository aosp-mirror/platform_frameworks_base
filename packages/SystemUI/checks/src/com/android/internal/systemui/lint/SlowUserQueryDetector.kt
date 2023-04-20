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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Checks for slow calls to ActivityManager.getCurrentUser() or UserManager.getUserInfo() and
 * suggests using UserTracker instead. For more info, see: http://go/multi-user-in-systemui-slides.
 */
@Suppress("UnstableApiUsage")
class SlowUserQueryDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return listOf("getCurrentUser", "getUserInfo")
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator
        if (
            evaluator.isStatic(method) &&
                method.name == "getCurrentUser" &&
                method.containingClass?.qualifiedName == "android.app.ActivityManager"
        ) {
            context.report(
                issue = ISSUE_SLOW_USER_ID_QUERY,
                location = context.getNameLocation(node),
                message =
                    "Use `UserTracker.getUserId()` instead of `ActivityManager.getCurrentUser()`"
            )
        }
        if (
            !evaluator.isStatic(method) &&
                method.name == "getUserInfo" &&
                method.containingClass?.qualifiedName == "android.os.UserManager"
        ) {
            context.report(
                issue = ISSUE_SLOW_USER_INFO_QUERY,
                location = context.getNameLocation(node),
                message = "Use `UserTracker.getUserInfo()` instead of `UserManager.getUserInfo()`"
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE_SLOW_USER_ID_QUERY: Issue =
            Issue.create(
                id = "SlowUserIdQuery",
                briefDescription = "User ID queried using ActivityManager",
                explanation =
                    """
                    `ActivityManager.getCurrentUser()` uses a blocking binder call and is slow. \
                    Instead, inject a `UserTracker` and call `UserTracker.getUserId()`.
                    """,
                moreInfo = "http://go/multi-user-in-systemui-slides",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(SlowUserQueryDetector::class.java, Scope.JAVA_FILE_SCOPE)
            )

        @JvmField
        val ISSUE_SLOW_USER_INFO_QUERY: Issue =
            Issue.create(
                id = "SlowUserInfoQuery",
                briefDescription = "User info queried using UserManager",
                explanation =
                    """
                    `UserManager.getUserInfo()` uses a blocking binder call and is slow. \
                    Instead, inject a `UserTracker` and call `UserTracker.getUserInfo()`.
                    """,
                moreInfo = "http://go/multi-user-in-systemui-slides",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(SlowUserQueryDetector::class.java, Scope.JAVA_FILE_SCOPE)
            )
    }
}

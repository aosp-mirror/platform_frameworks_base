/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.google.android.lint

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
 * Lint Detector that finds issues with improper usages of the non-user getter methods of Settings
 */
@Suppress("UnstableApiUsage")
class CallingSettingsNonUserGetterMethodsDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> = listOf(
            "getString",
            "getInt",
            "getLong",
            "getFloat"
    )

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator
        if (evaluator.isMemberInClass(method, "android.provider.Settings.Secure") ||
                evaluator.isMemberInClass(method, "android.provider.Settings.System")
        ) {
            val message = getIncidentMessageNonUserGetterMethods(getMethodSignature(method))
            context.report(ISSUE_NON_USER_GETTER_CALLED, node, context.getNameLocation(node),
                    message)
        }
    }

    private fun getMethodSignature(method: PsiMethod) =
            method.containingClass
                    ?.qualifiedName
                    ?.let { "$it#${method.name}" }
                    ?: method.name

    companion object {
        @JvmField
        val ISSUE_NON_USER_GETTER_CALLED: Issue = Issue.create(
                id = "NonUserGetterCalled",
                briefDescription = "Non-ForUser Getter Method called to Settings",
                explanation = """
                    System process should not call the non-ForUser getter methods of \
                    `Settings.Secure` or `Settings.System`. For example, instead of \
                    `Settings.Secure.getInt()`, use `Settings.Secure.getIntForUser()` instead. \
                    This will make sure that the correct Settings value is retrieved.
                    """,
                category = Category.CORRECTNESS,
                priority = 6,
                severity = Severity.ERROR,
                implementation = Implementation(
                        CallingSettingsNonUserGetterMethodsDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                )
        )

        fun getIncidentMessageNonUserGetterMethods(methodSignature: String) =
                "`$methodSignature()` called from system process. " +
                        "Please call `${methodSignature}ForUser()` instead. "
    }
}

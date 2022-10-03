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

/** Detects usage of Context.getSystemService() and suggests to use an injected instance instead. */
@Suppress("UnstableApiUsage")
class NonInjectedServiceDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return listOf("getSystemService")
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator
        if (
            !evaluator.isStatic(method) &&
                method.name == "getSystemService" &&
                method.containingClass?.qualifiedName == "android.content.Context"
        ) {
            context.report(
                ISSUE,
                method,
                context.getNameLocation(node),
                "Use @Inject to get the handle to a system-level services instead of using " +
                    "Context.getSystemService()"
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "NonInjectedService",
                briefDescription =
                    "System-level services should be retrieved using " +
                        "@Inject instead of Context.getSystemService().",
                explanation =
                    "Context.getSystemService() should be avoided because it makes testing " +
                        "difficult. Instead, use an injected service. For example, " +
                        "instead of calling Context.getSystemService(UserManager.class), " +
                        "use @Inject and add UserManager to the constructor",
                category = Category.CORRECTNESS,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(NonInjectedServiceDetector::class.java, Scope.JAVA_FILE_SCOPE)
            )
    }
}

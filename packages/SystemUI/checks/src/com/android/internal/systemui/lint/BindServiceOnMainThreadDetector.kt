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

import com.android.SdkConstants.CLASS_CONTEXT
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType

/**
 * Warns if {@code Context.bindService}, {@code Context.bindServiceAsUser}, or {@code
 * Context.unbindService} is not called on a {@code WorkerThread}
 */
@Suppress("UnstableApiUsage")
class BindServiceOnMainThreadDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return listOf("bindService", "bindServiceAsUser", "unbindService")
    }

    private fun hasWorkerThreadAnnotation(
        context: JavaContext,
        annotated: PsiModifierListOwner?
    ): Boolean {
        return context.evaluator.getAnnotations(annotated, inHierarchy = true).any { uAnnotation ->
            uAnnotation.qualifiedName == "androidx.annotation.WorkerThread"
        }
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (context.evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT)) {
            if (
                !hasWorkerThreadAnnotation(context, node.getParentOfType(UMethod::class.java)) &&
                    !hasWorkerThreadAnnotation(context, node.getParentOfType(UClass::class.java))
            ) {
                context.report(
                    issue = ISSUE,
                    location = context.getLocation(node),
                    message =
                        "This method should be annotated with `@WorkerThread` because " +
                            "it calls ${method.name}",
                )
            }
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "BindServiceOnMainThread",
                briefDescription = "Service bound or unbound on main thread",
                explanation =
                    """
                    Binding and unbinding services are synchronous calls to `ActivityManager`. \
                    They usually take multiple milliseconds to complete. If called on the main \
                    thread, it will likely cause missed frames. To fix it, use a `@Background \
                    Executor` and annotate the calling method with `@WorkerThread`.
                    """,
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        BindServiceOnMainThreadDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )
    }
}

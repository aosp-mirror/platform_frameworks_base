/*
 * Copyright (C) 2024 The Android Open Source Project
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
 *
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
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.uast.UCallExpression

/** Detects bad usages of Kotlin Flows */
class FlowDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf(
            FUN_MUTABLE_SHARED_FLOW,
            FUN_SHARE_IN,
        )

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (
            method.name == FUN_MUTABLE_SHARED_FLOW &&
                method.parent.isTopLevelKtOrJavaMember() &&
                context.evaluator.getPackage(method)?.qualifiedName == PACKAGE_FLOW
        ) {
            context.report(
                issue = SHARED_FLOW_CREATION,
                location = context.getNameLocation(node),
                message =
                    "`MutableSharedFlow()` creates a new shared flow, which has poor performance " +
                        "characteristics"
            )
        }
        if (
            method.name == FUN_SHARE_IN &&
                getTypeOfExtensionMethod(method)?.resolve()?.qualifiedName == CLASS_FLOW
        ) {
            context.report(
                issue = SHARED_FLOW_CREATION,
                location = context.getNameLocation(node),
                message =
                    "`shareIn()` creates a new shared flow, which has poor performance " +
                        "characteristics"
            )
        }
    }

    companion object {
        @JvmStatic
        val SHARED_FLOW_CREATION =
            Issue.create(
                id = "SharedFlowCreation",
                briefDescription = "Shared flow creation",
                explanation =
                    """
                            Shared flows scale poorly with the number of collectors in use due to
                            their internal buffering mechanism and reliance on thread
                            synchronization. They can also cause memory leaks when used incorrectly.
                            If possible, use `StateFlow` instead.
                """,
                moreInfo = "http://go//sysui-shared-flow",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.ERROR,
                implementation =
                    Implementation(
                        FlowDetector::class.java,
                        Scope.JAVA_FILE_SCOPE,
                    ),
            )

        fun getTypeOfExtensionMethod(method: PsiMethod): PsiClassType? {
            // If this is an extension method whose return type matches receiver
            val parameterList = method.parameterList
            if (parameterList.parametersCount > 0) {
                val firstParameter = parameterList.getParameter(0)
                if (firstParameter is PsiParameter && firstParameter.name.startsWith("\$this\$")) {
                    return firstParameter.type as? PsiClassType
                }
            }
            return null
        }
    }
}

private const val PACKAGE_FLOW = "kotlinx.coroutines.flow"
private const val FUN_MUTABLE_SHARED_FLOW = "MutableSharedFlow"
private const val FUN_SHARE_IN = "shareIn"
private const val CLASS_FLOW = "$PACKAGE_FLOW.Flow"

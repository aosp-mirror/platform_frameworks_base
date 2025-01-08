/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import com.android.tools.lint.detector.api.getReceiver
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.getContainingUFile

/**
 * Detects test function naming violations regarding use of the backtick-wrapped space-allowed
 * feature of Kotlin functions.
 */
class RunTestShouldUseKosmosDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames() = listOf("runTest")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (method.getReceiver()?.qualifiedName == "kotlinx.coroutines.test.TestScope") {

            val imports =
                node.getContainingUFile()?.imports.orEmpty().mapNotNull {
                    it.importReference?.asSourceString()
                }
            if (imports.any { it == "com.android.systemui.kosmos.Kosmos" }) {
                context.report(
                    issue = ISSUE,
                    scope = node,
                    location = context.getLocation(node.methodIdentifier),
                    message =
                        "Prefer Kosmos.runTest to TestScope.runTest in sysui tests that use Kosmos.  go/kosmos-runtest",
                )
                super.visitMethodCall(context, node, method)
            }
        }
    }

    companion object {
        @JvmStatic
        val ISSUE =
            Issue.create(
                id = "RunTestShouldUseKosmos",
                briefDescription = "When you can, use Kosmos.runTest instead of TestScope.runTest.",
                explanation =
                    """
                    Kosmos.runTest helps to ensure that the test uses the same coroutine
                    dispatchers that are used in Kosmos fixtures, preventing subtle bugs.
                    See go/kosmos-runtest
                """,
                category = Category.TESTING,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        RunTestShouldUseKosmosDetector::class.java,
                        Scope.JAVA_FILE_SCOPE,
                    ),
            )
    }
}

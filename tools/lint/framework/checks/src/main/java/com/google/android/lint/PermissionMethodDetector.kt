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

package com.google.android.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.getUMethod
import com.intellij.psi.PsiType
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.getContainingUMethod

/**
 * Stops incorrect usage of {@link PermissionMethod}
 * TODO: add tests once re-enabled (b/240445172, b/247542171)
 */
class PermissionMethodDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UAnnotation::class.java, UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        PermissionMethodHandler(context)

    private inner class PermissionMethodHandler(val context: JavaContext) : UElementHandler() {
        override fun visitMethod(node: UMethod) {
            if (hasPermissionMethodAnnotation(node)) return
            if (onlyCallsPermissionMethod(node)) {
                val location = context.getLocation(node.javaPsi.modifierList)
                val fix = fix()
                    .annotate(ANNOTATION_PERMISSION_METHOD)
                    .range(location)
                    .autoFix()
                    .build()

                context.report(
                    ISSUE_CAN_BE_PERMISSION_METHOD,
                    location,
                    "Annotate method with @PermissionMethod",
                    fix
                )
            }
        }

        override fun visitAnnotation(node: UAnnotation) {
            if (node.qualifiedName != ANNOTATION_PERMISSION_METHOD) return
            val method = node.getContainingUMethod() ?: return

            if (!isPermissionMethodReturnType(method)) {
                context.report(
                    ISSUE_PERMISSION_METHOD_USAGE,
                    context.getLocation(node),
                    """
                            Methods annotated with `@PermissionMethod` should return `void`, \
                            `boolean`, or `@PackageManager.PermissionResult int`."
                    """.trimIndent()
                )
            }

            if (method.returnType == PsiType.INT &&
                method.annotations.none { it.hasQualifiedName(ANNOTATION_PERMISSION_RESULT) }
            ) {
                context.report(
                    ISSUE_PERMISSION_METHOD_USAGE,
                    context.getLocation(node),
                    """
                            Methods annotated with `@PermissionMethod` that return `int` should \
                            also be annotated with `@PackageManager.PermissionResult.`"
                    """.trimIndent()
                )
            }
        }
    }

    companion object {

        private val EXPLANATION_PERMISSION_METHOD_USAGE = """
            `@PermissionMethod` should annotate methods that ONLY perform permission lookups. \
            Said methods should return `boolean`, `@PackageManager.PermissionResult int`, or return \
            `void` and potentially throw `SecurityException`.
        """.trimIndent()

        @JvmField
        val ISSUE_PERMISSION_METHOD_USAGE = Issue.create(
            id = "PermissionMethodUsage",
            briefDescription = "@PermissionMethod used incorrectly",
            explanation = EXPLANATION_PERMISSION_METHOD_USAGE,
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                PermissionMethodDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            ),
            enabledByDefault = true
        )

        private val EXPLANATION_CAN_BE_PERMISSION_METHOD = """
            Methods that only call other methods annotated with @PermissionMethod (and do NOTHING else) can themselves \
            be annotated with @PermissionMethod.  For example:
            ```
            void wrapperHelper() {
              // Context.enforceCallingPermission is annotated with @PermissionMethod
              context.enforceCallingPermission(SOME_PERMISSION)
            }
            ```
        """.trimIndent()

        @JvmField
        val ISSUE_CAN_BE_PERMISSION_METHOD = Issue.create(
            id = "CanBePermissionMethod",
            briefDescription = "Method can be annotated with @PermissionMethod",
            explanation = EXPLANATION_CAN_BE_PERMISSION_METHOD,
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.WARNING,
            implementation = Implementation(
                PermissionMethodDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            ),
            enabledByDefault = false
        )

        private fun isPermissionMethodReturnType(method: UMethod): Boolean =
            listOf(PsiType.VOID, PsiType.INT, PsiType.BOOLEAN).contains(method.returnType)

        /**
         * Identifies methods that...
         * DO call other methods annotated with @PermissionMethod
         * DO NOT do anything else
         */
        private fun onlyCallsPermissionMethod(method: UMethod): Boolean {
            val body = method.uastBody as? UBlockExpression ?: return false
            if (body.expressions.isEmpty()) return false
            for (expression in body.expressions) {
                when (expression) {
                    is UQualifiedReferenceExpression -> {
                        if (!isPermissionMethodCall(expression.selector)) return false
                    }
                    is UReturnExpression -> {
                        if (!isPermissionMethodCall(expression.returnExpression)) return false
                    }
                    is UCallExpression -> {
                        if (!isPermissionMethodCall(expression)) return false
                    }
                    is UIfExpression -> {
                        if (expression.thenExpression !is UReturnExpression) return false
                        if (!isPermissionMethodCall(expression.condition)) return false
                    }
                    else -> return false
                }
            }
            return true
        }

        private fun isPermissionMethodCall(expression: UExpression?): Boolean {
            return when (expression) {
                is UQualifiedReferenceExpression ->
                    return isPermissionMethodCall(expression.selector)
                is UCallExpression -> {
                    val calledMethod = expression.resolve()?.getUMethod() ?: return false
                    return hasPermissionMethodAnnotation(calledMethod)
                }
                else -> false
            }
        }

        private fun hasPermissionMethodAnnotation(method: UMethod): Boolean = method.annotations
                .any { it.hasQualifiedName(ANNOTATION_PERMISSION_METHOD) }
    }
}

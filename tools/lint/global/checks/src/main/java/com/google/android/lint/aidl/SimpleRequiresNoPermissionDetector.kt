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
 */

package com.google.android.lint.aidl

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Ensures all AIDL implementations hosted by system_server which don't call other methods are
 * annotated with @RequiresNoPermission. AIDL Interfaces part of `exemptAidlInterfaces` are skipped
 * during this search to ensure the detector targets only new AIDL Interfaces.
 */
class SimpleRequiresNoPermissionDetector : AidlImplementationDetector() {
    override fun visitAidlMethod(
        context: JavaContext,
        node: UMethod,
        interfaceName: String,
        body: UBlockExpression
    ) {
        if (!isSystemServicePath(context)) return
        if (context.evaluator.isAbstract(node)) return

        val fullyQualifiedInterfaceName =
            getContainingAidlInterfaceQualified(context, node) ?: return
        if (exemptAidlInterfaces.contains(fullyQualifiedInterfaceName)) return

        if (node.hasAnnotation(ANNOTATION_REQUIRES_NO_PERMISSION)) return

        if (!isCallingMethod(node)) {
            context.report(
                ISSUE_SIMPLE_REQUIRES_NO_PERMISSION,
                node,
                context.getLocation(node),
                """
                    Method ${node.name} doesn't perform any permission checks, meaning it should \
                    be annotated with @RequiresNoPermission.
                """.trimMargin()
            )
        }
    }

    private fun isCallingMethod(node: UMethod): Boolean {
        val uCallExpressionVisitor = UCallExpressionVisitor()
        node.accept(uCallExpressionVisitor)

        return uCallExpressionVisitor.isCallingMethod
    }

    /**
     * Visits the body of a `UMethod` and determines if it encounters a `UCallExpression` which is
     * a `UastCallKind.METHOD_CALL`. `isCallingMethod` will hold the result of the search procedure.
     */
    private class UCallExpressionVisitor : AbstractUastVisitor() {
        var isCallingMethod = false

        override fun visitElement(node: UElement): Boolean {
            // Stop the search early when a method call has been found.
            return isCallingMethod
        }

        override fun visitCallExpression(node: UCallExpression): Boolean {
            if (node.kind != UastCallKind.METHOD_CALL) return false

            isCallingMethod = true
            return true
        }
    }

    companion object {

        private val EXPLANATION = """
            Method implementations of AIDL Interfaces hosted by the `system_server` which do not
            call any other methods should be annotated with @RequiresNoPermission. That is because
            not calling any other methods implies that the method does not perform any permission
            checking.

            Please migrate to an @RequiresNoPermission annotation.
        """.trimIndent()

        @JvmField
        val ISSUE_SIMPLE_REQUIRES_NO_PERMISSION = Issue.create(
            id = "SimpleRequiresNoPermission",
            briefDescription = "System Service APIs not calling other methods should use @RNP",
            explanation = EXPLANATION,
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                SimpleRequiresNoPermissionDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            ),
        )
    }
}

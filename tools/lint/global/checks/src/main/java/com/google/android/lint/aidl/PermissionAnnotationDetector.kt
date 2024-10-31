/*
 * Copyright (C) 2023 The Android Open Source Project
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
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UMethod

/**
 * Ensures all AIDL-generated methods are annotated.
 *
 * This detector is run on system_server to validate that any method that may
 * be exposed via an AIDL interface is permission-annotated. That is, it must
 * have one of the following annotation:
 *   - @EnforcePermission
 *   - @RequiresNoPermission
 *   - @PermissionManuallyEnforced
 */
class PermissionAnnotationDetector : AidlImplementationDetector() {

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

        if (AIDL_PERMISSION_ANNOTATIONS.any { node.hasAnnotation(it) }) return

        context.report(
            ISSUE_MISSING_PERMISSION_ANNOTATION,
            node,
            context.getLocation(node),
            """
                ${node.name} should be annotated with either @EnforcePermission, \
                @RequiresNoPermission or @PermissionManuallyEnforced.
            """.trimMargin()
        )
    }

    companion object {

        private val EXPLANATION_MISSING_PERMISSION_ANNOTATION = """
          Interfaces that are exposed by system_server are required to have an annotation which
          denotes the type of permission enforced. There are 3 possible options:
            - @EnforcePermission
            - @RequiresNoPermission
            - @PermissionManuallyEnforced
          See the documentation of each annotation for further details.

          The annotation on the Java implementation must be the same that the AIDL interface
          definition. This is verified by a lint in the build system.
          """.trimIndent()

        @JvmField
        val ISSUE_MISSING_PERMISSION_ANNOTATION = Issue.create(
            id = "MissingPermissionAnnotation",
            briefDescription = "No permission annotation on exposed AIDL interface.",
            explanation = EXPLANATION_MISSING_PERMISSION_ANNOTATION,
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                PermissionAnnotationDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
    }
}

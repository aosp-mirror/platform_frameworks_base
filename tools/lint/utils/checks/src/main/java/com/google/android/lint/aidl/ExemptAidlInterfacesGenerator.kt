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
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UMethod

/**
 * Generates a set of fully qualified AIDL Interface names present in the entire source tree with
 * the following requirement: their implementations have to be inside directories whose path
 * prefixes match `systemServicePathPrefixes`.
 */
class ExemptAidlInterfacesGenerator : AidlImplementationDetector() {
    private val targetExemptAidlInterfaceNames = mutableSetOf<String>()

    // We could've improved performance by visiting classes rather than methods, however, this lint
    // check won't be run regularly, hence we've decided not to add extra overrides to
    // AidlImplementationDetector.
    override fun visitAidlMethod(
        context: JavaContext,
        node: UMethod,
        interfaceName: String,
        body: UBlockExpression
    ) {
        if (!isSystemServicePath(context)) return

        val fullyQualifiedInterfaceName =
            getContainingAidlInterfaceQualified(context, node) ?: return

        targetExemptAidlInterfaceNames.add("\"$fullyQualifiedInterfaceName\",")
    }

    override fun afterCheckEachProject(context: Context) {
        if (targetExemptAidlInterfaceNames.isEmpty()) return

        val message = targetExemptAidlInterfaceNames.joinToString("\n")

        context.report(
            ISSUE_PERMISSION_ANNOTATION_EXEMPT_AIDL_INTERFACES,
            context.getLocation(context.project.dir),
            "\n" + message + "\n",
        )
    }

    companion object {
        @JvmField
        val ISSUE_PERMISSION_ANNOTATION_EXEMPT_AIDL_INTERFACES = Issue.create(
            id = "PermissionAnnotationExemptAidlInterfaces",
            briefDescription = "Returns a set of all AIDL interfaces",
            explanation = """
                Produces the exemptAidlInterfaces set used by PermissionAnnotationDetector
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.INFORMATIONAL,
            implementation = Implementation(
                ExemptAidlInterfacesGenerator::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
    }
}

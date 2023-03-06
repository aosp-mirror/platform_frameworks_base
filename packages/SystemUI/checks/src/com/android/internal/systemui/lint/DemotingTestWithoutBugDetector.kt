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

package com.android.internal.systemui.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement

class DemotingTestWithoutBugDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UAnnotation::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitAnnotation(node: UAnnotation) {
                if (node.qualifiedName !in DEMOTING_ANNOTATION) {
                    return
                }
                val bugId = node.findAttributeValue("bugId")!!.evaluate() as Int
                if (bugId <= 0) {
                    val location = context.getLocation(node)
                    val message = "Please attach a bug id to track demoted test"
                    context.report(ISSUE, node, location, message)
                }
            }
        }
    }

    companion object {
        val DEMOTING_ANNOTATION =
            listOf("androidx.test.filters.FlakyTest", "android.platform.test.annotations.FlakyTest")

        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "DemotingTestWithoutBug",
                briefDescription = "Demoting a test without attaching a bug.",
                explanation =
                    """
                    Annotations (`@FlakyTest`) demote tests to an unmonitored \
                    test suite. Please set the `bugId` field in such annotations to track \
                    the test status.
                    """,
                category = Category.TESTING,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        DemotingTestWithoutBugDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )
    }
}

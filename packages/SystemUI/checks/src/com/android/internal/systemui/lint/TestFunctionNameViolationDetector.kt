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

import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UElement
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.kotlin.KotlinUAnnotation
import org.jetbrains.uast.kotlin.KotlinUMethod

/**
 * Detects test function naming violations regarding use of the backtick-wrapped space-allowed
 * feature of Kotlin functions.
 */
class TestFunctionNameViolationDetector : Detector(), SourceCodeScanner {

    override fun applicableAnnotations(): List<String> = listOf(ANNOTATION)
    override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean = true

    @Suppress("UnstableApiUsage")
    override fun visitAnnotationUsage(
        context: JavaContext,
        element: UElement,
        annotationInfo: AnnotationInfo,
        usageInfo: AnnotationUsageInfo,
    ) {
        (element as? KotlinUAnnotation)?.getParentOfType(KotlinUMethod::class.java)?.let { method ->
            if (method.name.contains(" ")) {
                context.report(
                    issue = ISSUE,
                    scope = method.nameIdentifier,
                    location = context.getLocation(method.nameIdentifier),
                    message =
                        "Spaces are not allowed in test names. Use pascalCase_withUnderScores" +
                            " instead.",
                )
            }
        }
    }

    companion object {
        private const val ANNOTATION = "org.junit.Test"

        @JvmStatic
        val ISSUE =
            Issue.create(
                id = "TestFunctionNameViolation",
                briefDescription = "Spaces not allowed in test function names.",
                explanation =
                    """
                    We don't allow test function names because it leads to issues with our test
                    harness system (for example, see b/277739595). Please use
                    pascalCase_withUnderScores instead.
                """,
                category = Category.TESTING,
                priority = 8,
                severity = Severity.FATAL,
                implementation =
                    Implementation(
                        TestFunctionNameViolationDetector::class.java,
                        Scope.JAVA_FILE_SCOPE,
                    ),
            )
    }
}

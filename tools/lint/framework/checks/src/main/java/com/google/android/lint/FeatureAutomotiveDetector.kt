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

package com.google.android.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import java.util.EnumSet
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UReferenceExpression

/**
 * A detector to check the usage of PackageManager.hasSystemFeature("
 * android.hardware.type.automotive") in CTS tests.
 */
class FeatureAutomotiveDetector : Detector(), SourceCodeScanner {

    companion object {

        val EXPLANATION =
            """
            This class uses PackageManager.hasSystemFeature(\"android.hardware.type.automotive\") \
            or other equivalent methods. \
            If it is used to make a CTS test behave differently on AAOS, you should use \
            @RequireAutomotive or @RequireNotAutomotive instead; otherwise, please ignore this \
            warning. See https://g3doc.corp.google.com/wireless/android/partner/compatibility/\
            g3doc/dev/write-a-test/index.md#write-a-test-that-behaves-differently-on-aaos
            """

        val ISSUE: Issue =
            Issue.create(
                id = "UsingFeatureAutomotiveInCTS",
                briefDescription =
                    "PackageManager.hasSystemFeature(\"" +
                        " android.hardware.type.automotive\") is used in CTS tests",
                explanation = EXPLANATION,
                category = Category.TESTING,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        FeatureAutomotiveDetector::class.java,
                        EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES)
                    )
            )
    }

    override fun getApplicableMethodNames() =
        listOf("hasSystemFeature", "hasFeature", "hasDeviceFeature", "bypassTestForFeatures")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        node.valueArguments.forEach {
            val value =
                when (it) {
                    is ULiteralExpression -> it.value
                    is UReferenceExpression -> ConstantEvaluator.evaluate(context, it)
                    else -> null
                }
            if (value is String && value == "android.hardware.type.automotive") {
                context.report(
                    issue = ISSUE,
                    location = context.getNameLocation(method),
                    message = EXPLANATION
                )
            }
        }
    }
}

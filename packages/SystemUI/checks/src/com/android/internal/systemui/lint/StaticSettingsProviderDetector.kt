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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

private const val CLASS_SETTINGS = "android.provider.Settings"

/**
 * Detects usage of static methods in android.provider.Settings and suggests to use an injected
 * settings provider instance instead.
 */
@Suppress("UnstableApiUsage")
class StaticSettingsProviderDetector : Detector(), SourceCodeScanner {
    override fun getApplicableMethodNames(): List<String> {
        return listOf(
            "getFloat",
            "getInt",
            "getLong",
            "getString",
            "getUriFor",
            "putFloat",
            "putInt",
            "putLong",
            "putString"
        )
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val evaluator = context.evaluator
        val className = method.containingClass?.qualifiedName
        if (
            className != "$CLASS_SETTINGS.Global" &&
                className != "$CLASS_SETTINGS.Secure" &&
                className != "$CLASS_SETTINGS.System"
        ) {
            return
        }
        if (!evaluator.isStatic(method)) {
            return
        }

        val subclassName = className.substring(CLASS_SETTINGS.length + 1)

        context.report(
            issue = ISSUE,
            location = context.getNameLocation(node),
            message = "`@Inject` a ${subclassName}Settings instead"
        )
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "StaticSettingsProvider",
                briefDescription = "Static settings provider usage",
                explanation =
                    """
                    Static settings provider methods, such as `Settings.Global.putInt()`, should \
                    not be used because they make testing difficult. Instead, use an injected \
                    settings provider. For example, instead of calling `Settings.Secure.getInt()`, \
                    annotate the class constructor with `@Inject` and add `SecureSettings` to the \
                    parameters.
                    """,
                category = Category.CORRECTNESS,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        StaticSettingsProviderDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )
    }
}

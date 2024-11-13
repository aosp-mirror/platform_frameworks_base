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
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParentOfType

/**
 * Checks if registerContentObserver/registerContentObserverAsUser/unregisterContentObserver is
 * called on a ContentResolver (or subclasses), and directs the caller to using
 * com.android.systemui.util.settings.SettingsProxy or its sub-classes.
 */
@Suppress("UnstableApiUsage")
class RegisterContentObserverViaContentResolverDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return CONTENT_RESOLVER_METHOD_LIST
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val classQualifiedName = node.getParentOfType(UClass::class.java)?.qualifiedName
        if (classQualifiedName in CLASSNAME_ALLOWLIST) {
            // Don't warn for class we want the developers to use.
            return
        }

        val evaluator = context.evaluator
        if (evaluator.isMemberInSubClassOf(method, "android.content.ContentResolver")) {
            context.report(
                issue = CONTENT_RESOLVER_ERROR,
                location = context.getNameLocation(node),
                message =
                    "`ContentResolver.${method.name}()` should be replaced with " +
                        "an appropriate interface API call, for eg. " +
                        "`<SettingsProxy>/<UserSettingsProxy>.${method.name}()`"
            )
        }
    }

    companion object {
        @JvmField
        val CONTENT_RESOLVER_ERROR: Issue =
            Issue.create(
                id = "RegisterContentObserverViaContentResolver",
                briefDescription =
                    "Content observer registration done via `ContentResolver`" +
                        "instead of `SettingsProxy or child interfaces.`",
                // lint trims indents and converts \ to line continuations
                explanation =
                    """
                        Use registerContentObserver/unregisterContentObserver methods in \
                        `SettingsProxy`, `UserSettingsProxy` or `GlobalSettings` class instead of \
                        using `ContentResolver.registerContentObserver` or \
                        `ContentResolver.unregisterContentObserver`.""",
                category = Category.PERFORMANCE,
                priority = 10,
                severity = Severity.ERROR,
                implementation =
                    Implementation(
                        RegisterContentObserverViaContentResolverDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )

        private val CLASSNAME_ALLOWLIST =
            listOf(
                "com.android.systemui.util.settings.SettingsProxy",
                "com.android.systemui.util.settings.UserSettingsProxy",
                "com.android.systemui.util.settings.GlobalSettings",
                "com.android.systemui.util.settings.SecureSettings",
                "com.android.systemui.util.settings.SystemSettings"
            )

        private val CONTENT_RESOLVER_METHOD_LIST =
            listOf(
                "registerContentObserver",
                "registerContentObserverAsUser",
                "unregisterContentObserver"
            )
    }
}

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

/**
 * Checks if the synchronous APIs like registerContentObserverSync/unregisterContentObserverSync are
 * invoked for SettingsProxy or it's sub-classes, and raise a warning notifying the caller to use
 * the asynchronous/suspend APIs instead.
 */
@Suppress("UnstableApiUsage")
class RegisterContentObserverSyncViaSettingsProxyDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return SYNC_METHOD_LIST
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {

        val evaluator = context.evaluator
        if (evaluator.isMemberInSubClassOf(method, SETTINGS_PROXY_CLASS)) {
            context.report(
                issue = SYNC_WARNING,
                location = context.getNameLocation(node),
                message =
                    "`Avoid using ${method.name}()` if calling the API is not " +
                        "required on the main thread. Instead use an appropriate async interface " +
                        "API call for eg. `registerContentObserver()` or " +
                        "`registerContentObserverAsync()`."
            )
        }
    }

    companion object {
        val SYNC_WARNING: Issue =
            Issue.create(
                id = "RegisterContentObserverSyncWarning",
                briefDescription =
                    "Synchronous content observer registration API called " +
                        "instead of the async APIs.`",
                // lint trims indents and converts \ to line continuations
                explanation =
                    """
                        ContentObserver registration/de-registration done via \
                        `SettingsProxy.registerContentObserverSync` will block the main thread \
                        and may cause missed frames. Instead, use \
                        `SettingsProxy.registerContentObserver()` or \
                        `SettingsProxy.registerContentObserverAsync()`. These APIs will ensure \
                        that the registrations/de-registrations happen sequentially on a
                        background worker thread.""",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        RegisterContentObserverSyncViaSettingsProxyDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )

        private val SYNC_METHOD_LIST =
            listOf(
                "registerContentObserverSync",
                "unregisterContentObserverSync",
                "registerContentObserverForUserSync"
            )

        private val SETTINGS_PROXY_CLASS = "com.android.systemui.util.settings.SettingsProxy"
    }
}

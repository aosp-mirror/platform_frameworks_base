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

import com.android.SdkConstants.CLASS_CONTEXT
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

@Suppress("UnstableApiUsage")
class RegisterReceiverViaContextDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return listOf("registerReceiver", "registerReceiverAsUser", "registerReceiverForAllUsers")
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (context.evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT)) {
            context.report(
                    issue = ISSUE,
                    location = context.getNameLocation(node),
                    message = "Register `BroadcastReceiver` using `BroadcastDispatcher` instead " +
                    "of `Context`"
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                    id = "RegisterReceiverViaContext",
                    briefDescription = "Blocking broadcast registration",
                    // lint trims indents and converts \ to line continuations
                    explanation = """
                            `Context.registerReceiver()` is a blocking call to the system server, \
                            making it very likely that you'll drop a frame. Please use \
                            `BroadcastDispatcher` instead, which registers the receiver on a \
                             background thread. `BroadcastDispatcher` also improves our visibility \
                             into ANRs.""",
                            moreInfo = "http://go/identifying-broadcast-threads",
                    category = Category.PERFORMANCE,
                    priority = 8,
                    severity = Severity.WARNING,
                    implementation = Implementation(RegisterReceiverViaContextDetector::class.java,
                            Scope.JAVA_FILE_SCOPE)
            )
    }
}

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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass

/**
 * Checks if any class has implemented the `Dumpable` interface but has not registered itself with
 * the `DumpManager`.
 */
@Suppress("UnstableApiUsage")
class DumpableNotRegisteredDetector : Detector(), SourceCodeScanner {

    private var isDumpable: Boolean = false
    private var isCoreStartable: Boolean = false
    private var hasRegisterCall: Boolean = false
    private var classLocation: Location? = null

    override fun beforeCheckFile(context: Context) {
        isDumpable = false
        isCoreStartable = false
        hasRegisterCall = false
        classLocation = null
    }

    override fun applicableSuperClasses(): List<String> {
        return listOf(DUMPABLE_CLASS_NAME)
    }

    override fun getApplicableMethodNames(): List<String> {
        return listOf("registerDumpable", "registerNormalDumpable", "registerCriticalDumpable")
    }

    override fun visitClass(context: JavaContext, declaration: UClass) {
        if (declaration.isInterface || context.evaluator.isAbstract(declaration)) {
            // Don't require interfaces or abstract classes to call `register` (assume the full
            // implementations will call it). This also means that we correctly don't warn for the
            // `Dumpable` interface itself.
            return
        }

        classLocation = context.getNameLocation(declaration)

        val superTypeClassNames = declaration.superTypes.mapNotNull { it.resolve()?.qualifiedName }
        isDumpable = superTypeClassNames.contains(DUMPABLE_CLASS_NAME)
        isCoreStartable = superTypeClassNames.contains(CORE_STARTABLE_CLASS_NAME)
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (context.evaluator.isMemberInSubClassOf(method, DUMP_MANAGER_CLASS_NAME)) {
            hasRegisterCall = true
        }
    }

    override fun afterCheckFile(context: Context) {
        if (!isDumpable) {
            return
        }
        if (isDumpable && isCoreStartable) {
            // CoreStartables will be automatically registered, so classes that implement
            // CoreStartable do not need a `register` call.
            return
        }

        if (!hasRegisterCall) {
            context.report(
                issue = ISSUE,
                location = classLocation!!,
                message =
                    "Any class implementing `Dumpable` must call " +
                        "`DumpManager.registerNormalDumpable` or " +
                        "`DumpManager.registerCriticalDumpable`",
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "DumpableNotRegistered",
                briefDescription = "Dumpable not registered with DumpManager.",
                explanation =
                    """
                    This class has implemented the `Dumpable` interface, but it has not registered \
                    itself with the `DumpManager`. This means that the class will never actually \
                    be dumped. Please call `DumpManager.registerNormalDumpable` or \
                    `DumpManager.registerCriticalDumpable` in the class's constructor or \
                    initialization method.""",
                category = Category.CORRECTNESS,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(DumpableNotRegisteredDetector::class.java, Scope.JAVA_FILE_SCOPE)
            )

        private const val DUMPABLE_CLASS_NAME = "com.android.systemui.Dumpable"
        private const val CORE_STARTABLE_CLASS_NAME = "com.android.systemui.CoreStartable"
        private const val DUMP_MANAGER_CLASS_NAME = "com.android.systemui.dump.DumpManager"
    }
}

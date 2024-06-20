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
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod

/**
 * Prevents binding Activities, Services, and BroadcastReceivers as Singletons in the Dagger graph.
 *
 * It is OK to mark a BroadcastReceiver as singleton as long as it is being constructed/injected and
 * registered directly in the code. If instead it is declared in the manifest, and we let Android
 * construct it for us, we also need to let Android destroy it for us, so don't allow marking it as
 * singleton.
 */
class SingletonAndroidComponentDetector : Detector(), SourceCodeScanner {
    override fun applicableAnnotations(): List<String> {
        return listOf(
            "com.android.systemui.dagger.SysUISingleton",
        )
    }

    override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean =
        type == AnnotationUsageType.DEFINITION

    override fun visitAnnotationUsage(
        context: JavaContext,
        element: UElement,
        annotationInfo: AnnotationInfo,
        usageInfo: AnnotationUsageInfo
    ) {
        if (element !is UAnnotation) {
            return
        }

        val parent = element.uastParent ?: return

        if (isInvalidBindingMethod(parent)) {
            context.report(
                ISSUE,
                element,
                context.getLocation(element),
                "Do not bind Activities, Services, or BroadcastReceivers as Singleton."
            )
        } else if (isInvalidClassDeclaration(parent)) {
            context.report(
                ISSUE,
                element,
                context.getLocation(element),
                "Do not mark Activities or Services as Singleton."
            )
        }
    }

    private fun isInvalidBindingMethod(parent: UElement): Boolean {
        if (parent !is UMethod) {
            return false
        }

        if (
            parent.returnType?.canonicalText !in
                listOf(
                    "android.app.Activity",
                    "android.app.Service",
                    "android.content.BroadcastReceiver",
                )
        ) {
            return false
        }

        if (
            !MULTIBIND_ANNOTATIONS.all { it in parent.annotations.map { it.qualifiedName } } &&
                !MULTIPROVIDE_ANNOTATIONS.all { it in parent.annotations.map { it.qualifiedName } }
        ) {
            return false
        }
        return true
    }

    private fun isInvalidClassDeclaration(parent: UElement): Boolean {
        if (parent !is UClass) {
            return false
        }

        if (
            parent.javaPsi.superClass?.qualifiedName !in
                listOf(
                    "android.app.Activity",
                    "android.app.Service",
                    // Fine to mark BroadcastReceiver as singleton in this scenario
                )
        ) {
            return false
        }

        return true
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "SingletonAndroidComponent",
                briefDescription = "Activity, Service, or BroadcastReceiver marked as Singleton",
                explanation =
                    """Activities, Services, and BroadcastReceivers are created and destroyed by
                        the Android System Server. Marking them with a Dagger scope
                        results in them being cached and reused by Dagger. Trying to reuse a
                        component like this will make for a very bad time.""",
                category = Category.CORRECTNESS,
                priority = 10,
                severity = Severity.ERROR,
                moreInfo =
                    "https://developer.android.com/guide/components/activities/process-lifecycle",
                // Note that JAVA_FILE_SCOPE also includes Kotlin source files.
                implementation =
                    Implementation(
                        SingletonAndroidComponentDetector::class.java,
                        Scope.JAVA_FILE_SCOPE
                    )
            )

        private val MULTIBIND_ANNOTATIONS =
            listOf("dagger.Binds", "dagger.multibindings.IntoMap", "dagger.multibindings.ClassKey")

        val MULTIPROVIDE_ANNOTATIONS =
            listOf(
                "dagger.Provides",
                "dagger.multibindings.IntoMap",
                "dagger.multibindings.ClassKey"
            )
    }
}

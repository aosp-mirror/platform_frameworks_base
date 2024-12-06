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

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiParameter
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getContainingUFile

class ShadeDisplayAwareDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UClass::class.java)

    override fun createUastHandler(context: JavaContext) =
        object : UElementHandler() {
            override fun visitClass(node: UClass) {
                for (constructor in node.constructors) {
                    // Visit all injected constructors in shade-relevant packages
                    if (!constructor.hasAnnotation(INJECT_ANNOTATION)) continue
                    if (!isInRelevantShadePackage(node)) continue
                    if (IGNORED_PACKAGES.contains(node.qualifiedName)) continue

                    for (parameter in constructor.parameterList.parameters) {
                        if (parameter.shouldReport()) {
                            context.report(
                                issue = ISSUE,
                                scope = parameter.declarationScope,
                                location = context.getNameLocation(parameter),
                                message = reportMsg(className = parameter.type.presentableText),
                            )
                        }
                    }
                }
            }
        }

    companion object {
        private const val INJECT_ANNOTATION = "javax.inject.Inject"
        private const val APPLICATION_ANNOTATION =
            "com.android.systemui.dagger.qualifiers.Application"
        private const val GLOBAL_CONFIG_ANNOTATION = "com.android.systemui.common.ui.GlobalConfig"
        private const val SHADE_DISPLAY_AWARE_ANNOTATION =
            "com.android.systemui.shade.ShadeDisplayAware"

        private const val CONTEXT = "android.content.Context"
        private const val WINDOW_MANAGER = "android.view.WindowManager"
        private const val LAYOUT_INFLATER = "android.view.LayoutInflater"
        private const val RESOURCES = "android.content.res.Resources"
        private const val CONFIG_STATE = "com.android.systemui.common.ui.ConfigurationState"
        private const val CONFIG_CONTROLLER =
            "com.android.systemui.statusbar.policy.ConfigurationController"
        private const val CONFIG_INTERACTOR =
            "com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor"

        private val CONTEXT_DEPENDENT_SHADE_CLASSES =
            setOf(
                CONTEXT,
                WINDOW_MANAGER,
                LAYOUT_INFLATER,
                RESOURCES,
                CONFIG_STATE,
                CONFIG_CONTROLLER,
                CONFIG_INTERACTOR,
            )

        private val CONFIG_CLASSES = setOf(CONFIG_STATE, CONFIG_CONTROLLER, CONFIG_INTERACTOR)

        private val SHADE_WINDOW_PACKAGES =
            listOf(
                "com.android.systemui.biometrics",
                "com.android.systemui.bouncer",
                "com.android.systemui.keyboard.docking.ui.viewmodel",
                "com.android.systemui.qs",
                "com.android.systemui.shade",
                "com.android.systemui.statusbar.notification",
                "com.android.systemui.unfold.domain.interactor",
            )

        private val IGNORED_PACKAGES =
            setOf(
                "com.android.systemui.biometrics.UdfpsController",
                "com.android.systemui.qs.customize.TileAdapter",
            )

        private fun PsiParameter.shouldReport(): Boolean {
            val className = type.canonicalText

            // check if the parameter is a context-dependent class relevant to shade
            if (className !in CONTEXT_DEPENDENT_SHADE_CLASSES) return false
            // check if it has @ShadeDisplayAware
            if (hasAnnotation(SHADE_DISPLAY_AWARE_ANNOTATION)) return false
            // check if its a @Application-annotated Context
            if (className == CONTEXT && hasAnnotation(APPLICATION_ANNOTATION)) return false
            // check if its a @GlobalConfig-annotated ConfigurationState, ConfigurationController
            // or ConfigurationInteractor
            if (className in CONFIG_CLASSES && hasAnnotation(GLOBAL_CONFIG_ANNOTATION)) return false

            return true
        }

        private fun isInRelevantShadePackage(node: UClass): Boolean {
            val packageName = node.getContainingUFile()?.packageName
            if (packageName.isNullOrBlank()) return false
            return SHADE_WINDOW_PACKAGES.any { relevantPackage ->
                packageName.startsWith(relevantPackage)
            }
        }

        private fun reportMsg(className: String) =
            "UI elements of the shade window should use " +
                "ShadeDisplayAware-annotated $className, as the shade might move between windows, " +
                "and only @ShadeDisplayAware resources are updated with the new configuration " +
                "correctly. Failures to do so might result in wrong dimensions for shade window " +
                "classes (e.g. using the wrong density or theme). If the usage of $className is " +
                "not related to display specific configuration or UI, then there is technically " +
                "no need to use the annotation, and you can annotate the class with " +
                "@SuppressLint(\"ShadeDisplayAwareContextChecker\")/" +
                "@Suppress(\"ShadeDisplayAwareContextChecker\")".trimMargin()

        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "ShadeDisplayAwareContextChecker",
                briefDescription = "Using non-ShadeDisplayAware component within shade",
                explanation =
                    """
                Any context-dependent components (Resources, LayoutInflater, ConfigurationState,
                etc.) being injected into Shade-relevant classes must have the @ShadeDisplayAware
                annotation to ensure they work with when the shade is moved to a different display.
                When the shade is moved, the configuration might change, and only @ShadeDisplayAware
                components will update accordingly to reflect the new display.
            """
                        .trimIndent(),
                category = Category.CORRECTNESS,
                priority = 8,
                severity = Severity.ERROR,
                implementation =
                    Implementation(ShadeDisplayAwareDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}

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
 *
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
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UImportStatement

/**
 * Detects violations of the Dependency Rule of Clean Architecture.
 *
 * The rule states that code in each layer may only depend on code in the same layer or the layer
 * directly "beneath" that layer in the layer diagram.
 *
 * In System UI, we have three layers; from top to bottom, they are: ui, domain, and data. As a
 * convention, was used packages with those names to place code in the appropriate layer. We also
 * make an exception and allow for shared models to live under a separate package named "shared" to
 * avoid code duplication.
 *
 * For more information, please see go/sysui-arch.
 */
@Suppress("UnstableApiUsage")
class CleanArchitectureDependencyViolationDetector : Detector(), Detector.UastScanner {
    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UFile::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitFile(node: UFile) {
                // Check which Clean Architecture layer this file belongs to:
                matchingLayer(node.packageName)?.let { layer ->
                    // The file matches with a Clean Architecture layer. Let's check all of its
                    // imports.
                    node.imports.forEach { importStatement ->
                        visitImportStatement(context, layer, importStatement)
                    }
                }
            }
        }
    }

    private fun visitImportStatement(
        context: JavaContext,
        layer: Layer,
        importStatement: UImportStatement,
    ) {
        val importText = importStatement.importReference?.asSourceString() ?: return
        val importedLayer = matchingLayer(importText) ?: return

        // Now check whether the layer of the file may depend on the layer of the import.
        if (!layer.mayDependOn(importedLayer)) {
            context.report(
                issue = ISSUE,
                scope = importStatement,
                location = context.getLocation(importStatement),
                message =
                    "The ${layer.packageNamePart} layer may not depend on" +
                        " the ${importedLayer.packageNamePart} layer.",
            )
        }
    }

    private fun matchingLayer(packageName: String): Layer? {
        val packageNameParts = packageName.split(".").toSet()
        return Layer.values()
            .filter { layer -> packageNameParts.contains(layer.packageNamePart) }
            .takeIf { it.size == 1 }
            ?.first()
    }

    private enum class Layer(
        val packageNamePart: String,
        val canDependOn: Set<Layer>,
    ) {
        SHARED(
            packageNamePart = "shared",
            canDependOn = emptySet(), // The shared layer may not depend on any other layer.
        ),
        DATA(
            packageNamePart = "data",
            canDependOn = setOf(SHARED),
        ),
        DOMAIN(
            packageNamePart = "domain",
            canDependOn = setOf(SHARED, DATA),
        ),
        UI(
            packageNamePart = "ui",
            canDependOn = setOf(DOMAIN, SHARED),
        ),
        ;

        fun mayDependOn(otherLayer: Layer): Boolean {
            return this == otherLayer || canDependOn.contains(otherLayer)
        }
    }

    companion object {
        @JvmStatic
        val ISSUE =
            Issue.create(
                id = "CleanArchitectureDependencyViolation",
                briefDescription = "Violation of the Clean Architecture Dependency Rule.",
                explanation =
                    """
                    Following the \"Dependency Rule\" from Clean Architecture, every layer of code \
                    can only depend code in its own layer or code in the layer directly \
                    \"beneath\" it. Therefore, the UI layer can only depend on the" Domain layer \
                    and the Domain layer can only depend on the Data layer. We" do make an \
                    exception to allow shared models to exist and be shared across layers by \
                    placing them under shared/model, which should be done with care. For more \
                    information about Clean Architecture in System UI, please see go/sysui-arch. \
                    NOTE: if your code is not using Clean Architecture, please feel free to ignore \
                    this warning.
                """,
                category = Category.CORRECTNESS,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                    Implementation(
                        CleanArchitectureDependencyViolationDetector::class.java,
                        Scope.JAVA_FILE_SCOPE,
                    ),
            )
    }
}

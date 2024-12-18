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
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UImportStatement

class CollectAsStateDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> {
        return listOf(UFile::class.java)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitFile(node: UFile) {
                node.imports.forEach { importStatement ->
                    visitImportStatement(context, importStatement)
                }
            }
        }
    }

    private fun visitImportStatement(
        context: JavaContext,
        importStatement: UImportStatement,
    ) {
        val importText = importStatement.importReference?.asSourceString() ?: return
        if (ILLEGAL_IMPORT == importText) {
            context.report(
                issue = ISSUE,
                scope = importStatement,
                location = context.getLocation(importStatement),
                message = "collectAsState considered harmful",
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE =
            Issue.create(
                id = "OverlyEagerCollectAsState",
                briefDescription = "collectAsState considered harmful",
                explanation =
                    """
                go/sysui-compose#collect-as-state

                Don't use collectAsState as it will set up a coroutine that keeps collecting from a
                flow until its coroutine scope becomes inactive. This prevents the work from being
                properly paused while the surrounding lifecycle becomes paused or stopped and is
                therefore considered harmful.

                Instead, use Flow.collectAsStateWithLifecycle(initial: T) or
                StateFlow.collectAsStateWithLifecycle(). These APIs correctly pause the collection
                coroutine while the lifecycle drops below the specified minActiveState (which
                defaults to STARTED meaning that it will pause when the Compose-hosting window
                becomes invisible).
            """
                        .trimIndent(),
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.ERROR,
                implementation =
                    Implementation(
                        CollectAsStateDetector::class.java,
                        Scope.JAVA_FILE_SCOPE,
                    ),
            )

        private val ILLEGAL_IMPORT = "androidx.compose.runtime.collectAsState"
    }
}

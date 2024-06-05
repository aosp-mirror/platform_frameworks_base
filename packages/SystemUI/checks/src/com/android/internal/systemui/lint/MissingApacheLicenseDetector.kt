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

import com.android.ide.common.blame.SourcePosition
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.SourceCodeScanner
import java.time.Year
import org.jetbrains.uast.UComment
import org.jetbrains.uast.UFile

/**
 * Checks if every AOSP Java/Kotlin source code file is starting with Apache license information.
 */
class MissingApacheLicenseDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes() = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        return object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val firstComment = node.allCommentsInFile.firstOrNull()
                // Normally we don't need to explicitly handle suppressing case and just return
                // error as usual with indicating node and lint will ignore it for us. But here
                // suppressing will be applied on top of comment that doesn't exist so we don't have
                // node to return - it's a bit of corner case
                if (firstComment != null && firstComment.isSuppressingComment()) {
                    return
                }
                if (firstComment == null || !firstComment.isLicenseComment()) {
                    val firstLineOfFile =
                        Location.create(
                            context.file,
                            SourcePosition(/* lineNumber= */ 1, /* column= */ 1, /* offset= */ 0)
                        )
                    context.report(
                        issue = ISSUE,
                        location = firstLineOfFile,
                        message =
                            "License header is missing\n" +
                                "Please add the following copyright and license header to the" +
                                " beginning of the file:\n\n" +
                                copyrightHeader
                    )
                }
            }
        }
    }

    private fun UComment.isSuppressingComment(): Boolean {
        val suppressingComment =
            "//noinspection ${MissingApacheLicenseDetector::class.java.simpleName}"
        return text.contains(suppressingComment)
    }

    private fun UComment.isLicenseComment(): Boolean {
        // We probably don't want to compare full copyright header in case there are some small
        // discrepancies in already existing files, e.g. year. We could do regexp but it should be
        // good enough if this detector deals with missing
        // license header instead of incorrect license header
        return text.contains("Apache License")
    }

    private val copyrightHeader: String
        get() =
            """
            /*
             * Copyright (C) ${Year.now().value} The Android Open Source Project
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *     http://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */
            """
                .trimIndent()
                .trim()

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "MissingApacheLicenseDetector",
                briefDescription = "File is missing Apache license information",
                explanation =
                    """
                        Every source code file should have copyright and license information \
                        attached at the beginning.""",
                category = Category.COMPLIANCE,
                priority = 8,
                // This check is disabled by default so that it is not accidentally used by internal
                // modules that have different silencing. This check can be enabled in Soong using
                // the following configuration:
                //   lint: {
                //    warning_checks: ["MissingApacheLicenseDetector"],
                //   }
                enabledByDefault = false,
                implementation =
                    Implementation(MissingApacheLicenseDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}

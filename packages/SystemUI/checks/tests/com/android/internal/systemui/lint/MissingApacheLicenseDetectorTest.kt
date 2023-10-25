/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import java.time.Year
import org.junit.Test

class MissingApacheLicenseDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector {
        return MissingApacheLicenseDetector()
    }

    override fun getIssues(): List<Issue> {
        return listOf(
            MissingApacheLicenseDetector.ISSUE,
        )
    }

    @Test
    fun testHasCopyright() {
        lint()
            .files(
                kotlin(
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

                    package test.pkg.name

                    class MyTest
                    """
                        .trimIndent()
                )
            )
            .issues(MissingApacheLicenseDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testDoesntHaveCopyright() {
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg.name

                    class MyTest
                    """
                        .trimIndent()
                )
            )
            // skipping mode SUPPRESSIBLE because lint tries to add @Suppress to class which
            // probably doesn't make much sense for license header (which is far above it) and for
            // kotlin files that can have several classes. If someone really wants to omit header
            // they can do it with //noinspection
            .skipTestModes(TestMode.SUPPRESSIBLE)
            .issues(MissingApacheLicenseDetector.ISSUE)
            .run()
            .expectContains("License header is missing")
    }
}

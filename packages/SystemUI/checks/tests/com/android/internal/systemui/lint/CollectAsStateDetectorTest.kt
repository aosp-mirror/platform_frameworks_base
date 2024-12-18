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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class CollectAsStateDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector {
        return CollectAsStateDetector()
    }

    override fun getIssues(): List<Issue> {
        return listOf(
            CollectAsStateDetector.ISSUE,
        )
    }

    @Test
    fun testViolation() {
        lint()
            .files(COLLECT_AS_STATE_STUB, COLLECT_WITH_LIFECYCLE_AS_STATE_STUB, GOOD_FILE, BAD_FILE)
            .issues(CollectAsStateDetector.ISSUE)
            .run()
            .expect(
                """
src/com/android/internal/systemui/lint/Bad.kt:3: Error: collectAsState considered harmful [OverlyEagerCollectAsState]
import androidx.compose.runtime.collectAsState
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    @Test
    fun testNoViolation() {
        lint()
            .files(COLLECT_AS_STATE_STUB, COLLECT_WITH_LIFECYCLE_AS_STATE_STUB, GOOD_FILE)
            .issues(CollectAsStateDetector.ISSUE)
            .run()
            .expectClean()
    }

    companion object {
        private val COLLECT_AS_STATE_STUB =
            TestFiles.kotlin(
                """
                package androidx.compose.runtime

                fun collectAsState() {}
            """
                    .trimIndent()
            )
        private val COLLECT_WITH_LIFECYCLE_AS_STATE_STUB =
            TestFiles.kotlin(
                """
                package androidx.lifecycle.compose

                fun collectAsStateWithLifecycle() {}
            """
                    .trimIndent()
            )

        private val BAD_FILE =
            TestFiles.kotlin(
                """
                package com.android.internal.systemui.lint

                import androidx.compose.runtime.collectAsState

                class Bad
            """
                    .trimIndent()
            )

        private val GOOD_FILE =
            TestFiles.kotlin(
                """
                package com.android.internal.systemui.lint

                import androidx.lifecycle.compose.collectAsStateWithLifecycle

                class Good
            """
                    .trimIndent()
            )
    }
}

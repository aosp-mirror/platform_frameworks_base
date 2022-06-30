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

package com.android.systemui.testing.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.android.systemui.compose.theme.SystemUITheme
import com.android.systemui.testing.screenshot.ScreenshotActivity
import com.android.systemui.testing.screenshot.ScreenshotTestRule
import com.android.systemui.testing.screenshot.ScreenshotTestSpec
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** A rule for Compose screenshot diff tests. */
class ComposeScreenshotTestRule(testSpec: ScreenshotTestSpec) : TestRule {
    private val composeRule = createAndroidComposeRule<ScreenshotActivity>()
    private val screenshotRule = ScreenshotTestRule(testSpec)

    private val delegate = RuleChain.outerRule(screenshotRule).around(composeRule)

    override fun apply(base: Statement, description: Description): Statement {
        return delegate.apply(base, description)
    }

    /**
     * Compare [content] with the golden image identified by [goldenIdentifier] in the context of
     * [testSpec].
     */
    fun screenshotTest(
        goldenIdentifier: String,
        content: @Composable () -> Unit,
    ) {
        // Make sure that the activity draws full screen and fits the whole display instead of the
        // system bars.
        val activity = composeRule.activity
        activity.mainExecutor.execute { activity.window.setDecorFitsSystemWindows(false) }

        // Set the content using the AndroidComposeRule to make sure that the Activity is set up
        // correctly.
        composeRule.setContent {
            SystemUITheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()

        val view = (composeRule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        screenshotRule.screenshotTest(goldenIdentifier, view)
    }
}

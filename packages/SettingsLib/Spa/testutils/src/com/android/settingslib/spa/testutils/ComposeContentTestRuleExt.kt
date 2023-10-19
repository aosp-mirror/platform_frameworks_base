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

package com.android.settingslib.spa.testutils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.android.settingslib.spa.framework.theme.SettingsTheme

/** Blocks until the found a semantics node that match the given condition. */
fun ComposeContentTestRule.waitUntilExists(matcher: SemanticsMatcher) = waitUntil {
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
}

/** Blocks until the timeout is reached. */
fun ComposeContentTestRule.delay(timeoutMillis: Long = 1_000) = try {
    waitUntil(timeoutMillis) { false }
} catch (_: ComposeTimeoutException) {
    // Expected
}

/** Finds a text node that within dialog. */
fun ComposeContentTestRule.onDialogText(text: String): SemanticsNodeInteraction =
    onNode(hasAnyAncestor(isDialog()) and hasText(text))

fun ComposeTestRule.rootWidth(): Dp = onRoot().getUnclippedBoundsInRoot().width

fun ComposeTestRule.rootHeight(): Dp = onRoot().getUnclippedBoundsInRoot().height

/**
 * Constant to emulate very big but finite constraints
 */
private val sizeAssertionMaxSize = 5000.dp

private const val SIZE_ASSERTION_TAG = "containerForSizeAssertion"

fun ComposeContentTestRule.setContentForSizeAssertions(
    parentMaxWidth: Dp = sizeAssertionMaxSize,
    parentMaxHeight: Dp = sizeAssertionMaxSize,
    // TODO : figure out better way to make it flexible
    content: @Composable () -> Unit
): SemanticsNodeInteraction {
    setContent {
        SettingsTheme {
            Surface {
                Box {
                    Box(
                        Modifier
                            .sizeIn(maxWidth = parentMaxWidth, maxHeight = parentMaxHeight)
                            .testTag(SIZE_ASSERTION_TAG)
                    ) {
                        content()
                    }
                }
            }
        }
    }

    return onNodeWithTag(SIZE_ASSERTION_TAG)
}

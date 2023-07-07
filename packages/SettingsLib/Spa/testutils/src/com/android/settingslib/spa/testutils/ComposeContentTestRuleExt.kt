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

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule

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

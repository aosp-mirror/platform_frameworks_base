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

package com.android.settingslib.spa.widget

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.tests.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IllustrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val DrawableId = SemanticsPropertyKey<Int>("DrawableResId")
    private var SemanticsPropertyReceiver.drawableId by DrawableId

    @Test
    fun image_displayed() {
        val resId = R.drawable.accessibility_captioning_banner
        composeTestRule.setContent {
            Illustration(
                resId = resId,
                resourceType = ResourceType.IMAGE,
                modifier = Modifier.semantics { drawableId = resId }
            )
        }

        fun hasDrawable(@DrawableRes id: Int): SemanticsMatcher =
            SemanticsMatcher.expectValue(DrawableId, id)

        val isIllustrationNode = hasAnyAncestor(hasDrawable(resId))
        composeTestRule.onAllNodes(hasDrawable(resId))
            .filterToOne(isIllustrationNode)
            .assertIsDisplayed()
    }

    private val RawId = SemanticsPropertyKey<Int>("RawResId")
    private var SemanticsPropertyReceiver.rawId by RawId

    @Test
    fun empty_lottie_not_displayed() {
        val resId = R.raw.empty
        composeTestRule.setContent {
            Illustration(
                resId = resId,
                resourceType = ResourceType.LOTTIE,
                modifier = Modifier.semantics { rawId = resId }
            )
        }

        fun hasRaw(@RawRes id: Int): SemanticsMatcher =
            SemanticsMatcher.expectValue(RawId, id)

        val isIllustrationNode = hasAnyAncestor(hasRaw(resId))
        composeTestRule.onAllNodes(hasRaw(resId))
            .filterToOne(isIllustrationNode)
            .assertIsNotDisplayed()
    }
}

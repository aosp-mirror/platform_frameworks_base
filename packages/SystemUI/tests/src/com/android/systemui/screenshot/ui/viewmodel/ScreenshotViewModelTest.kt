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

package com.android.systemui.screenshot.ui.viewmodel

import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.mock

@SmallTest
class ScreenshotViewModelTest {
    private val accessibilityManager: AccessibilityManager = mock(AccessibilityManager::class.java)
    private val appearance = ActionButtonAppearance(null, "Label", "Description")
    private val onclick = {}

    @Test
    fun testAddAction() {
        val viewModel = ScreenshotViewModel(accessibilityManager)

        assertThat(viewModel.actions.value).isEmpty()

        viewModel.addAction(appearance, true, onclick)

        assertThat(viewModel.actions.value).hasSize(1)

        val added = viewModel.actions.value[0]
        assertThat(added.appearance).isEqualTo(appearance)
        assertThat(added.onClicked).isEqualTo(onclick)
        assertThat(added.showDuringEntrance).isTrue()
    }

    @Test
    fun testRemoveAction() {
        val viewModel = ScreenshotViewModel(accessibilityManager)
        val firstId = viewModel.addAction(ActionButtonAppearance(null, "", ""), false, {})
        val secondId = viewModel.addAction(appearance, false, onclick)

        assertThat(viewModel.actions.value).hasSize(2)
        assertThat(firstId).isNotEqualTo(secondId)

        viewModel.removeAction(firstId)

        assertThat(viewModel.actions.value).hasSize(1)

        val remaining = viewModel.actions.value[0]
        assertThat(remaining.appearance).isEqualTo(appearance)
        assertThat(remaining.showDuringEntrance).isFalse()
        assertThat(remaining.onClicked).isEqualTo(onclick)
    }

    @Test
    fun testUpdateActionAppearance() {
        val viewModel = ScreenshotViewModel(accessibilityManager)
        val id = viewModel.addAction(appearance, false, onclick)
        val otherAppearance = ActionButtonAppearance(null, "Other", "Other")

        viewModel.updateActionAppearance(id, otherAppearance)

        assertThat(viewModel.actions.value).hasSize(1)
        val updated = viewModel.actions.value[0]
        assertThat(updated.appearance).isEqualTo(otherAppearance)
        assertThat(updated.onClicked).isEqualTo(onclick)
    }
}

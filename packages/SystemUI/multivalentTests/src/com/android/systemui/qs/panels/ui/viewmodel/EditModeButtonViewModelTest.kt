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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.fakeFalsingManager
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.editModeButtonViewModelFactory
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class EditModeButtonViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()

    val underTest = kosmos.editModeButtonViewModelFactory.create()

    @Test
    fun falsingFalseTap_editModeDoesntStart() =
        kosmos.runTest {
            val isEditing by collectLastValue(editModeViewModel.isEditing)

            fakeFalsingManager.setFalseTap(true)

            underTest.onButtonClick()
            runCurrent()

            assertThat(isEditing).isFalse()
        }

    @Test
    fun falsingNotFalseTap_editModeStarted() =
        kosmos.runTest {
            val isEditing by collectLastValue(editModeViewModel.isEditing)

            fakeFalsingManager.setFalseTap(false)

            underTest.onButtonClick()
            runCurrent()

            assertThat(isEditing).isTrue()
        }
}

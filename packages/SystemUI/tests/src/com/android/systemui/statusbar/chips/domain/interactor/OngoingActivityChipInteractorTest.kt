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

package com.android.systemui.statusbar.chips.domain.interactor

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor.Companion.createDialogLaunchOnClickListener
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlin.test.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class OngoingActivityChipInteractorTest : SysuiTestCase() {
    private val mockSystemUIDialog = mock<SystemUIDialog>()
    private val dialogDelegate = SystemUIDialog.Delegate { mockSystemUIDialog }
    private val dialogTransitionAnimator = mock<DialogTransitionAnimator>()

    private val chipBackgroundView = mock<ChipBackgroundContainer>()
    private val chipView =
        mock<View>().apply {
            whenever(
                    this.requireViewById<ChipBackgroundContainer>(
                        R.id.ongoing_activity_chip_background
                    )
                )
                .thenReturn(chipBackgroundView)
        }

    @Test
    fun createDialogLaunchOnClickListener_showsDialogOnClick() {
        val clickListener =
            createDialogLaunchOnClickListener(dialogDelegate, dialogTransitionAnimator)

        // Dialogs must be created on the main thread
        context.mainExecutor.execute {
            clickListener.onClick(chipView)
            verify(dialogTransitionAnimator)
                .showFromView(
                    eq(mockSystemUIDialog),
                    eq(chipBackgroundView),
                    eq(null),
                    anyBoolean(),
                )
        }
    }
}

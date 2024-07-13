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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickListener
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlin.test.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
class OngoingActivityChipViewModelTest : SysuiTestCase() {
    private val mockSystemUIDialog = mock<SystemUIDialog>()
    private val dialogDelegate = SystemUIDialog.Delegate { mockSystemUIDialog }

    @Test
    fun createDialogLaunchOnClickListener_showsDialogOnClick() {
        val clickListener =
            createDialogLaunchOnClickListener(
                dialogDelegate,
                logcatLogBuffer("OngoingActivityChipViewModelTest"),
                "tag",
            )

        // Dialogs must be created on the main thread
        context.mainExecutor.execute {
            clickListener.onClick(mock<View>())
            verify(mockSystemUIDialog).show()
        }
    }
}

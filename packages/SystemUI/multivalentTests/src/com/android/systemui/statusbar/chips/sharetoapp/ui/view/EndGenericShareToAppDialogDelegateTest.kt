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

package com.android.systemui.statusbar.chips.sharetoapp.ui.view

import android.content.DialogInterface
import android.content.applicationContext
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.mediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class EndGenericShareToAppDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val sysuiDialog = mock<SystemUIDialog>()
    private val underTest =
        EndGenericShareToAppDialogDelegate(
            kosmos.endMediaProjectionDialogHelper,
            kosmos.applicationContext,
            stopAction = kosmos.mediaProjectionChipInteractor::stopProjecting,
        )

    @Test
    fun positiveButton_clickStopsRecording() =
        kosmos.testScope.runTest {
            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isFalse()

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()
            verify(sysuiDialog).setPositiveButton(any(), clickListener.capture())
            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isTrue()
        }
}

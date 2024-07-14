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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.view

import android.content.DialogInterface
import android.content.applicationContext
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediarouter.data.repository.fakeMediaRouterRepository
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.casttootherdevice.domain.interactor.mediaRouterChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.CastDevice
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class EndGenericCastToOtherDeviceDialogDelegateTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val sysuiDialog = mock<SystemUIDialog>()
    private lateinit var underTest: EndGenericCastToOtherDeviceDialogDelegate

    @Test
    fun icon() {
        createAndSetDelegate()

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setIcon(R.drawable.ic_cast_connected)
    }

    @Test
    fun title() {
        createAndSetDelegate()

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setTitle(R.string.cast_to_other_device_stop_dialog_title)
    }

    @Test
    fun message_unknownDevice() {
        createAndSetDelegate(deviceName = null)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(R.string.cast_to_other_device_stop_dialog_message_generic)
            )
    }

    @Test
    fun message_hasDevice() {
        createAndSetDelegate(deviceName = "My Favorite Device")

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.cast_to_other_device_stop_dialog_message_generic_with_device,
                    "My Favorite Device",
                )
            )
    }

    @Test
    fun negativeButton() {
        createAndSetDelegate()

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setNegativeButton(R.string.close_dialog_button, null)
    }

    @Test
    fun positiveButton() =
        kosmos.testScope.runTest {
            createAndSetDelegate()

            // Set up a real device so the stop works correctly
            collectLastValue(kosmos.mediaRouterChipInteractor.mediaRouterCastingState)
            val device =
                CastDevice(
                    state = CastDevice.CastState.Connected,
                    id = "id",
                    name = "name",
                    description = "desc",
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            kosmos.fakeMediaRouterRepository.castDevices.value = listOf(device)
            // Let everything catch up to the repo value
            runCurrent()
            runCurrent()

            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(sysuiDialog)
                .setPositiveButton(
                    eq(R.string.cast_to_other_device_stop_dialog_button),
                    clickListener.capture()
                )

            // Verify that clicking the button stops the recording
            assertThat(kosmos.fakeMediaRouterRepository.lastStoppedDevice).isNull()

            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.fakeMediaRouterRepository.lastStoppedDevice).isEqualTo(device)
        }

    private fun createAndSetDelegate(deviceName: String? = null) {
        underTest =
            EndGenericCastToOtherDeviceDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                deviceName,
                stopAction = kosmos.mediaRouterChipInteractor::stopCasting,
            )
    }
}

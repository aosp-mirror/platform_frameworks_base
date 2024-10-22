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

package com.android.systemui.bluetooth.qsdialog

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.testing.TestableLooper
import android.widget.Button
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingDialogDelegateTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos()
    private val updateFlow = MutableSharedFlow<Unit>()
    private lateinit var underTest: AudioSharingDialogDelegate

    @Before
    fun setUp() {
        with(kosmos) {
            // TODO(b/364515243): use real object instead of mock
            whenever(deviceItemInteractor.deviceItemUpdateRequest).thenReturn(updateFlow)
            whenever(deviceItemInteractor.deviceItemUpdate)
                .thenReturn(MutableStateFlow(emptyList()))
            underTest = audioSharingDialogDelegate
        }
    }

    @Test
    fun testCreateDialog() =
        kosmos.testScope.runTest {
            val dialog = underTest.createDialog()
            assertThat(dialog).isInstanceOf(SystemUIDialog::class.java)
        }

    @Test
    fun testCreateDialog_showState() =
        with(kosmos) {
            testScope.runTest {
                val availableDeviceName = "name"
                whenever(cachedBluetoothDevice.name).thenReturn(availableDeviceName)
                val dialog = spy(underTest.createDialog())
                dialog.show()
                runCurrent()
                val subtitleTextView = dialog.findViewById<TextView>(R.id.subtitle)
                val switchActiveButton = dialog.findViewById<Button>(R.id.switch_active_button)
                val shareAudioButton = dialog.findViewById<Button>(R.id.share_audio_button)
                val subtitle =
                    context.getString(
                        R.string.quick_settings_bluetooth_audio_sharing_dialog_subtitle,
                        availableDeviceName,
                        ""
                    )
                val switchButtonText =
                    context.getString(
                        R.string.quick_settings_bluetooth_audio_sharing_dialog_switch_to_button,
                        availableDeviceName
                    )
                assertThat(subtitleTextView.text).isEqualTo(subtitle)
                assertThat(switchActiveButton.text).isEqualTo(switchButtonText)
                assertThat(switchActiveButton.hasOnClickListeners()).isTrue()
                assertThat(shareAudioButton.hasOnClickListeners()).isTrue()

                switchActiveButton.performClick()
                verify(dialog).dismiss()
            }
        }

    @Test
    fun testCreateDialog_hideState() =
        with(kosmos) {
            testScope.runTest {
                val dialog = spy(underTest.createDialog())
                dialog.show()
                runCurrent()
                updateFlow.emit(Unit)
                runCurrent()
                verify(dialog).dismiss()
            }
        }
}

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

package com.android.systemui.volume.panel.component.bottombar.ui.viewmodel

import android.app.ActivityManager
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.activityStarter
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import com.android.systemui.volume.panel.ui.viewmodel.volumePanelViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BottomBarViewModelTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Captor private lateinit var intentCaptor: ArgumentCaptor<Intent>

    @Captor private lateinit var activityStartedCaptor: ArgumentCaptor<ActivityStarter.Callback>

    private val kosmos = testKosmos()

    private lateinit var underTest: BottomBarViewModel

    private fun initUnderTest() {
        underTest =
            with(kosmos) {
                BottomBarViewModel(activityStarter, volumePanelViewModel, uiEventLogger)
            }
    }

    @Test
    fun onDoneClicked_hidesPanel() {
        with(kosmos) {
            testScope.runTest {
                initUnderTest()
                underTest.onDoneClicked()
                runCurrent()

                val volumePanelState by collectLastValue(volumePanelViewModel.volumePanelState)
                assertThat(volumePanelState!!.isVisible).isFalse()
            }
        }
    }

    @Test
    fun onSettingsClicked_dismissesPanelAndNavigatesToSettings() {
        with(kosmos) {
            testScope.runTest {
                initUnderTest()
                underTest.onSettingsClicked()

                runCurrent()

                verify(activityStarter)
                    .startActivityDismissingKeyguard(
                        /* intent = */ capture(intentCaptor),
                        /* onlyProvisioned = */ eq(false),
                        /* dismissShade = */ eq(true),
                        /* disallowEnterPictureInPictureWhileLaunching = */ eq(false),
                        /* callback = */ capture(activityStartedCaptor),
                        /* flags = */ eq(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                        /* animationController = */ eq(null),
                        /* userHandle = */ eq(null),
                    )
                assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_SOUND_SETTINGS)
                assertThat(uiEventLoggerFake.eventId(0))
                    .isEqualTo(VolumePanelUiEvent.VOLUME_PANEL_SOUND_SETTINGS_CLICKED.id)

                activityStartedCaptor.value.onActivityStarted(ActivityManager.START_SUCCESS)
                val volumePanelState by collectLastValue(volumePanelViewModel.volumePanelState)
                assertThat(volumePanelState!!.isVisible).isFalse()
            }
        }
    }
}

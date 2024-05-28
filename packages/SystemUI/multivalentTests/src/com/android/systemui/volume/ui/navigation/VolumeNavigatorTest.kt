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

package com.android.systemui.volume.ui.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.activityStarter
import com.android.systemui.testKosmos
import com.android.systemui.volume.domain.model.VolumePanelRoute
import com.android.systemui.volume.panel.domain.interactor.volumePanelGlobalStateInteractor
import com.android.systemui.volume.panel.ui.viewmodel.volumePanelViewModelFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class VolumeNavigatorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest: VolumeNavigator =
        with(kosmos) {
            VolumeNavigator(
                testScope.backgroundScope,
                testDispatcher,
                mock {},
                activityStarter,
                volumePanelViewModelFactory,
                mock {
                    on { create(any(), anyInt(), anyBoolean(), any()) }.thenReturn(mock {})
                    on { applicationContext }.thenReturn(context)
                },
                uiEventLoggerFake,
                volumePanelGlobalStateInteractor,
            )
        }

    @Test
    fun showNewVolumePanel_keyguardLocked_notShown() =
        with(kosmos) {
            testScope.runTest {
                val panelState by collectLastValue(volumePanelGlobalStateInteractor.globalState)

                underTest.openVolumePanel(VolumePanelRoute.COMPOSE_VOLUME_PANEL)
                runCurrent()

                assertThat(panelState!!.isVisible).isFalse()
            }
        }

    @Test
    fun showNewVolumePanel_keyguardUnlocked_shown() =
        with(kosmos) {
            testScope.runTest {
                whenever(activityStarter.dismissKeyguardThenExecute(any(), any(), anyBoolean()))
                    .then { (it.arguments[0] as ActivityStarter.OnDismissAction).onDismiss() }
                val panelState by collectLastValue(volumePanelGlobalStateInteractor.globalState)

                underTest.openVolumePanel(VolumePanelRoute.COMPOSE_VOLUME_PANEL)
                runCurrent()

                assertThat(panelState!!.isVisible).isTrue()
            }
        }
}

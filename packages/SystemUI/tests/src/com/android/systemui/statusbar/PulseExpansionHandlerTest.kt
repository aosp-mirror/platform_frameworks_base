/*
 * Copyright (c) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class PulseExpansionHandlerTest : SysuiTestCase() {

    private lateinit var pulseExpansionHandler: PulseExpansionHandler

    private val collapsedHeight = 300
    private val wakeUpCoordinator: NotificationWakeUpCoordinator = mock()
    private val bypassController: KeyguardBypassController = mock()
    private val headsUpManager: HeadsUpManager = mock()
    private val configurationController: ConfigurationController = mock()
    private val statusBarStateController: StatusBarStateController = mock()
    private val falsingManager: FalsingManager = mock()
    private val shadeInteractor: ShadeInteractor = mock()
    private val lockscreenShadeTransitionController: LockscreenShadeTransitionController = mock()
    private val dumpManager: DumpManager = mock()
    private val expandableView: ExpandableView = mock()

    @Before
    fun setUp() {
        whenever(expandableView.collapsedHeight).thenReturn(collapsedHeight)
        whenever(shadeInteractor.isQsExpanded).thenReturn(MutableStateFlow(false))

        pulseExpansionHandler =
            PulseExpansionHandler(
                mContext,
                wakeUpCoordinator,
                bypassController,
                headsUpManager,
                configurationController,
                statusBarStateController,
                falsingManager,
                shadeInteractor,
                lockscreenShadeTransitionController,
                dumpManager
            )
    }

    @Test
    fun resetChild_updateHeight() {
        whenever(expandableView.actualHeight).thenReturn(500)

        pulseExpansionHandler.reset(expandableView, animationDuration = 0)

        verify(expandableView, atLeast(1)).actualHeight = collapsedHeight
    }

    @Test
    fun resetChild_dontUpdateHeight() {
        whenever(expandableView.actualHeight).thenReturn(collapsedHeight)

        pulseExpansionHandler.reset(expandableView, animationDuration = 0)

        verify(expandableView, never()).actualHeight = anyInt()
    }
}

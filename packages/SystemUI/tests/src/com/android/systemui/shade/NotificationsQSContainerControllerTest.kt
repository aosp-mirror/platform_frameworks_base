/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableResources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.fragments.FragmentService
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class NotificationsQSContainerControllerTest : SysuiTestCase() {

    @Mock lateinit var view: NotificationsQuickSettingsContainer
    @Mock lateinit var navigationModeController: NavigationModeController
    @Mock lateinit var overviewProxyService: OverviewProxyService
    @Mock lateinit var shadeHeaderController: ShadeHeaderController
    @Mock lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    @Mock lateinit var fragmentService: FragmentService

    lateinit var underTest: NotificationsQSContainerController

    private lateinit var fakeResources: TestableResources

    private val delayableExecutor: DelayableExecutor = FakeExecutor(FakeSystemClock())

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeResources = TestableResources(context.resources)

        whenever(view.resources).thenReturn(fakeResources.resources)

        underTest =
            NotificationsQSContainerController(
                view,
                navigationModeController,
                overviewProxyService,
                shadeHeaderController,
                shadeExpansionStateManager,
                fragmentService,
                delayableExecutor,
            )
    }

    @Test
    fun testSmallScreen_updateResources_splitShadeHeightIsSet() {
        with(fakeResources) {
            addOverride(R.bool.config_use_large_screen_shade_header, false)
            addOverride(R.dimen.qs_header_height, 1)
            addOverride(R.dimen.large_screen_shade_header_height, 2)
        }

        underTest.updateResources()

        val captor = ArgumentCaptor.forClass(ConstraintSet::class.java)
        verify(view).applyConstraints(capture(captor))
        assertThat(captor.value.getHeight(R.id.split_shade_status_bar)).isEqualTo(1)
    }

    @Test
    fun testLargeScreen_updateResources_splitShadeHeightIsSet() {
        with(fakeResources) {
            addOverride(R.bool.config_use_large_screen_shade_header, true)
            addOverride(R.dimen.qs_header_height, 1)
            addOverride(R.dimen.large_screen_shade_header_height, 2)
        }

        underTest.updateResources()

        val captor = ArgumentCaptor.forClass(ConstraintSet::class.java)
        verify(view).applyConstraints(capture(captor))
        assertThat(captor.value.getHeight(R.id.split_shade_status_bar)).isEqualTo(2)
    }
}

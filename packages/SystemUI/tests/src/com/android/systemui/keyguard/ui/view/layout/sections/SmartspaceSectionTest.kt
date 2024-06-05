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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.GONE
import androidx.constraintlayout.widget.ConstraintSet.VISIBLE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class SmartspaceSectionTest : SysuiTestCase() {
    private lateinit var underTest: SmartspaceSection
    @Mock private lateinit var keyguardClockViewModel: KeyguardClockViewModel
    @Mock private lateinit var keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel
    @Mock private lateinit var lockscreenSmartspaceController: LockscreenSmartspaceController
    @Mock private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var keyguardSmartspaceInteractor: KeyguardSmartspaceInteractor
    @Mock private lateinit var blueprintInteractor: Lazy<KeyguardBlueprintInteractor>

    private val smartspaceView = View(mContext).also { it.id = sharedR.id.bc_smartspace_view }
    private val weatherView = View(mContext).also { it.id = sharedR.id.weather_smartspace_view }
    private val dateView = View(mContext).also { it.id = sharedR.id.date_smartspace_view }
    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var constraintSet: ConstraintSet

    private val clockShouldBeCentered = MutableStateFlow(false)
    private val hasCustomWeatherDataDisplay = MutableStateFlow(false)
    private val isWeatherVisibleFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
        underTest =
            SmartspaceSection(
                mContext,
                keyguardClockViewModel,
                keyguardSmartspaceViewModel,
                keyguardSmartspaceInteractor,
                lockscreenSmartspaceController,
                keyguardUnlockAnimationController,
                blueprintInteractor
            )
        constraintLayout = ConstraintLayout(mContext)
        whenever(lockscreenSmartspaceController.buildAndConnectView(any()))
            .thenReturn(smartspaceView)
        whenever(lockscreenSmartspaceController.buildAndConnectWeatherView(any()))
            .thenReturn(weatherView)
        whenever(lockscreenSmartspaceController.buildAndConnectDateView(any())).thenReturn(dateView)
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay)
            .thenReturn(hasCustomWeatherDataDisplay)
        whenever(keyguardClockViewModel.clockShouldBeCentered).thenReturn(clockShouldBeCentered)
        whenever(keyguardSmartspaceViewModel.isSmartspaceEnabled).thenReturn(true)
        whenever(keyguardSmartspaceViewModel.isWeatherVisible).thenReturn(isWeatherVisibleFlow)
        constraintSet = ConstraintSet()
    }

    @Test
    fun testAddViews_notSmartspaceEnabled() {
        whenever(keyguardSmartspaceViewModel.isSmartspaceEnabled).thenReturn(false)
        val constraintLayout = ConstraintLayout(mContext)
        underTest.addViews(constraintLayout)
        assertThat(smartspaceView.parent).isNull()
        assertThat(weatherView.parent).isNull()
        assertThat(dateView.parent).isNull()
    }

    @Test
    fun testAddViews_smartspaceEnabled_dateWeatherDecoupled() {
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(true)
        underTest.addViews(constraintLayout)
        assert(smartspaceView.parent == constraintLayout)
        assert(weatherView.parent == constraintLayout)
        assert(dateView.parent == constraintLayout)
    }

    @Test
    fun testAddViews_smartspaceEnabled_notDateWeatherDecoupled() {
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(false)
        underTest.addViews(constraintLayout)
        assert(smartspaceView.parent == constraintLayout)
        assert(weatherView.parent == null)
        assert(dateView.parent == null)
    }

    @Test
    fun testConstraintsWhenNotHasCustomWeatherDataDisplay() {
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(true)
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)
        assertWeatherSmartspaceConstrains(constraintSet)

        val smartspaceConstraints = constraintSet.getConstraint(smartspaceView.id)
        assertThat(smartspaceConstraints.layout.topToBottom).isEqualTo(dateView.id)

        val dateConstraints = constraintSet.getConstraint(dateView.id)
        assertThat(dateConstraints.layout.topToBottom).isEqualTo(R.id.lockscreen_clock_view)
    }

    @Test
    fun testConstraintsWhenHasCustomWeatherDataDisplay() {
        hasCustomWeatherDataDisplay.value = true
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)
        assertWeatherSmartspaceConstrains(constraintSet)

        val dateConstraints = constraintSet.getConstraint(dateView.id)
        assertThat(dateConstraints.layout.bottomToTop).isEqualTo(smartspaceView.id)
    }

    @Test
    fun testNormalDateWeatherVisibility() {
        isWeatherVisibleFlow.value = true
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)
        assertThat(constraintSet.getVisibility(weatherView.id)).isEqualTo(VISIBLE)

        isWeatherVisibleFlow.value = false
        underTest.applyConstraints(constraintSet)
        assertThat(constraintSet.getVisibility(weatherView.id)).isEqualTo(GONE)
        assertThat(constraintSet.getVisibility(dateView.id)).isEqualTo(VISIBLE)
    }
    @Test
    fun testCustomDateWeatherVisibility() {
        hasCustomWeatherDataDisplay.value = true
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)

        assertThat(constraintSet.getVisibility(weatherView.id)).isEqualTo(GONE)
        assertThat(constraintSet.getVisibility(dateView.id)).isEqualTo(GONE)
    }

    private fun assertWeatherSmartspaceConstrains(cs: ConstraintSet) {
        val weatherConstraints = cs.getConstraint(weatherView.id)
        assertThat(weatherConstraints.layout.topToTop).isEqualTo(dateView.id)
        assertThat(weatherConstraints.layout.bottomToBottom).isEqualTo(dateView.id)
        assertThat(weatherConstraints.layout.startToEnd).isEqualTo(dateView.id)
        assertThat(weatherConstraints.layout.startMargin).isEqualTo(4)
    }
}

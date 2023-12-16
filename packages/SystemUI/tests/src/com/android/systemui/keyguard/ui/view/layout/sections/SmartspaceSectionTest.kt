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
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class SmartspaceSectionTest : SysuiTestCase() {
    private lateinit var underTest: SmartspaceSection
    @Mock private lateinit var keyguardClockViewModel: KeyguardClockViewModel
    @Mock private lateinit var keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel
    @Mock private lateinit var lockscreenSmartspaceController: LockscreenSmartspaceController
    @Mock private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var hasCustomWeatherDataDisplay: StateFlow<Boolean>

    private val smartspaceView = View(mContext).also { it.id = View.generateViewId() }
    private val weatherView = View(mContext).also { it.id = View.generateViewId() }
    private val dateView = View(mContext).also { it.id = View.generateViewId() }
    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var constraintSet: ConstraintSet

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mSetFlagsRule.enableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
        underTest =
            SmartspaceSection(
                keyguardClockViewModel,
                keyguardSmartspaceViewModel,
                mContext,
                lockscreenSmartspaceController,
                keyguardUnlockAnimationController,
            )
        constraintLayout = ConstraintLayout(mContext)
        whenever(lockscreenSmartspaceController.buildAndConnectView(constraintLayout))
            .thenReturn(smartspaceView)
        whenever(lockscreenSmartspaceController.buildAndConnectWeatherView(constraintLayout))
            .thenReturn(weatherView)
        whenever(lockscreenSmartspaceController.buildAndConnectDateView(constraintLayout))
            .thenReturn(dateView)
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay)
            .thenReturn(hasCustomWeatherDataDisplay)
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
        whenever(keyguardSmartspaceViewModel.isSmartspaceEnabled).thenReturn(true)
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(true)
        underTest.addViews(constraintLayout)
        assert(smartspaceView.parent == constraintLayout)
        assert(weatherView.parent == constraintLayout)
        assert(dateView.parent == constraintLayout)
    }

    @Test
    fun testAddViews_smartspaceEnabled_notDateWeatherDecoupled() {
        whenever(keyguardSmartspaceViewModel.isSmartspaceEnabled).thenReturn(true)
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(false)
        underTest.addViews(constraintLayout)
        assert(smartspaceView.parent == constraintLayout)
        assert(weatherView.parent == null)
        assert(dateView.parent == null)
    }

    @Test
    fun testConstraintsWhenNotHasCustomWeatherDataDisplay() {
        whenever(keyguardSmartspaceViewModel.isSmartspaceEnabled).thenReturn(true)
        whenever(keyguardSmartspaceViewModel.isDateWeatherDecoupled).thenReturn(true)
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay.value).thenReturn(false)
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
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay.value).thenReturn(true)
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)
        assertWeatherSmartspaceConstrains(constraintSet)

        val dateConstraints = constraintSet.getConstraint(dateView.id)
        assertThat(dateConstraints.layout.bottomToTop).isEqualTo(smartspaceView.id)
    }

    @Test
    fun testNormalDateWeatherVisibility() {
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay.value).thenReturn(false)
        whenever(keyguardSmartspaceViewModel.isWeatherEnabled).thenReturn(true)
        underTest.addViews(constraintLayout)
        underTest.applyConstraints(constraintSet)
        assertThat(constraintSet.getVisibility(weatherView.id)).isEqualTo(VISIBLE)

        whenever(keyguardSmartspaceViewModel.isWeatherEnabled).thenReturn(false)
        underTest.applyConstraints(constraintSet)
        assertThat(constraintSet.getVisibility(weatherView.id)).isEqualTo(GONE)
        assertThat(constraintSet.getVisibility(dateView.id)).isEqualTo(VISIBLE)
    }
    @Test
    fun testCustomDateWeatherVisibility() {
        whenever(keyguardClockViewModel.hasCustomWeatherDataDisplay.value).thenReturn(true)
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

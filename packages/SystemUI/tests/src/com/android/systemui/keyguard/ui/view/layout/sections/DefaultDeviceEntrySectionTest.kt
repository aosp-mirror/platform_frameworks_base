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

import android.graphics.Point
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.LegacyLockIconViewController
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryBackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryForegroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@SmallTest
class DefaultDeviceEntrySectionTest : SysuiTestCase() {
    @Mock private lateinit var authController: AuthController
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var windowManager: WindowManager
    @Mock private lateinit var notificationPanelView: NotificationPanelView
    private lateinit var featureFlags: FakeFeatureFlags
    @Mock private lateinit var lockIconViewController: LegacyLockIconViewController
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var deviceEntryIconViewModel: DeviceEntryIconViewModel
    private lateinit var underTest: DefaultDeviceEntrySection

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)

        featureFlags =
            FakeFeatureFlagsClassic().apply { set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false) }
        underTest =
            DefaultDeviceEntrySection(
                TestScope().backgroundScope,
                authController,
                windowManager,
                context,
                notificationPanelView,
                featureFlags,
                { lockIconViewController },
                { deviceEntryIconViewModel },
                { mock(DeviceEntryForegroundViewModel::class.java) },
                { mock(DeviceEntryBackgroundViewModel::class.java) },
                { falsingManager },
                { mock(VibratorHelper::class.java) },
            )
    }

    @Test
    fun addViewsConditionally_migrateFlagOn() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        val constraintLayout = ConstraintLayout(context, null)
        underTest.addViews(constraintLayout)
        assertThat(constraintLayout.childCount).isGreaterThan(0)
    }

    @Test
    fun addViewsConditionally_migrateAndRefactorFlagsOn() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        val constraintLayout = ConstraintLayout(context, null)
        underTest.addViews(constraintLayout)
        assertThat(constraintLayout.childCount).isGreaterThan(0)
    }

    @Test
    fun addViewsConditionally_migrateFlagOff() {
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT)
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        val constraintLayout = ConstraintLayout(context, null)
        underTest.addViews(constraintLayout)
        assertThat(constraintLayout.childCount).isEqualTo(0)
    }

    @Test
    fun applyConstraints_udfps_refactor_off() {
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        val cs = ConstraintSet()
        underTest.applyConstraints(cs)

        val constraint = cs.getConstraint(R.id.lock_icon_view)

        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
    }

    @Test
    fun applyConstraints_udfps_refactor_on() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        whenever(deviceEntryIconViewModel.isUdfpsSupported).thenReturn(MutableStateFlow(false))
        val cs = ConstraintSet()
        underTest.applyConstraints(cs)

        val constraint = cs.getConstraint(R.id.device_entry_icon_view)

        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
    }

    @Test
    fun testCenterIcon_udfps_refactor_off() {
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        val cs = ConstraintSet()
        underTest.centerIcon(Point(5, 6), 1F, cs)

        val constraint = cs.getConstraint(R.id.lock_icon_view)

        assertThat(constraint.layout.mWidth).isEqualTo(2)
        assertThat(constraint.layout.mHeight).isEqualTo(2)
        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.topMargin).isEqualTo(5)
        assertThat(constraint.layout.startMargin).isEqualTo(4)
    }

    @Test
    fun testCenterIcon_udfps_refactor_on() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
        val cs = ConstraintSet()
        underTest.centerIcon(Point(5, 6), 1F, cs)

        val constraint = cs.getConstraint(R.id.device_entry_icon_view)

        assertThat(constraint.layout.mWidth).isEqualTo(2)
        assertThat(constraint.layout.mHeight).isEqualTo(2)
        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.topMargin).isEqualTo(5)
        assertThat(constraint.layout.startMargin).isEqualTo(4)
    }
}

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

package com.android.systemui.statusbar.phone

import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT
import com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT
import com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE
import com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT
import com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSLUCENT
import com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class PhoneStatusBarTransitionsTest : SysuiTestCase() {

    // PhoneStatusBarView does a lot of non-standard things when inflating, so just use mocks.
    private val batteryView = mock<View>()
    private val statusIcons = mock<View>()
    private val startIcons = mock<View>()
    private val statusBarView =
        mock<PhoneStatusBarView>().apply {
            whenever(this.context).thenReturn(mContext)
            whenever(this.findViewById<View>(R.id.battery)).thenReturn(batteryView)
            whenever(this.findViewById<View>(R.id.statusIcons)).thenReturn(statusIcons)
            whenever(this.findViewById<View>(R.id.status_bar_start_side_except_heads_up))
                .thenReturn(startIcons)
        }
    private val backgroundView = mock<View>().apply { whenever(this.context).thenReturn(mContext) }

    private val underTest: PhoneStatusBarTransitions by lazy {
        PhoneStatusBarTransitions(statusBarView, backgroundView).also {
            // The views' alphas will be set when PhoneStatusBarTransitions is created and we want
            // to ignore those in the tests, so clear those verifications here.
            reset(batteryView)
            reset(statusIcons)
            reset(startIcons)
        }
    }

    @Before
    fun setUp() {
        context.orCreateTestableResources.addOverride(
            R.dimen.status_bar_icon_drawing_alpha,
            RESOURCE_ALPHA,
        )
    }

    @Test
    fun transitionTo_lightsOutMode_batteryTranslucent() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT, /* animate= */ false)

        val alpha = batteryView.capturedAlpha()
        assertThat(alpha).isGreaterThan(0)
        assertThat(alpha).isLessThan(1)
    }

    @Test
    fun transitionTo_lightsOutMode_statusIconsHidden() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT, /* animate= */ false)

        assertThat(statusIcons.capturedAlpha()).isEqualTo(0)
    }

    @Test
    fun transitionTo_lightsOutMode_startIconsHidden() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT, /* animate= */ false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(0)
    }

    @Test
    fun transitionTo_lightsOutTransparentMode_batteryTranslucent() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT_TRANSPARENT, /* animate= */ false)

        val alpha = batteryView.capturedAlpha()
        assertThat(alpha).isGreaterThan(0)
        assertThat(alpha).isLessThan(1)
    }

    @Test
    fun transitionTo_lightsOutTransparentMode_statusIconsHidden() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT_TRANSPARENT, /* animate= */ false)

        assertThat(statusIcons.capturedAlpha()).isEqualTo(0)
    }

    @Test
    fun transitionTo_lightsOutTransparentMode_startIconsHidden() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT_TRANSPARENT, /* animate= */ false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(0)
    }

    @Test
    fun transitionTo_translucentMode_batteryIconShown() {
        underTest.transitionTo(/* mode= */ MODE_TRANSLUCENT, /* animate= */ false)

        assertThat(batteryView.capturedAlpha()).isEqualTo(1)
    }

    @Test
    fun transitionTo_semiTransparentMode_statusIconsShown() {
        underTest.transitionTo(/* mode= */ MODE_SEMI_TRANSPARENT, /* animate= */ false)

        assertThat(statusIcons.capturedAlpha()).isEqualTo(1)
    }

    @Test
    fun transitionTo_transparentMode_startIconsShown() {
        // Transparent is the default, so we need to switch to a different mode first
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)
        reset(startIcons)

        underTest.transitionTo(/* mode= */ MODE_TRANSPARENT, /* animate= */ false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(1)
    }

    @Test
    fun transitionTo_opaqueMode_batteryIconUsesResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)

        assertThat(batteryView.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    @Test
    fun transitionTo_opaqueMode_statusIconsUseResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)

        assertThat(statusIcons.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    @Test
    fun transitionTo_opaqueMode_startIconsUseResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    @Test
    fun onHeadsUpStateChanged_true_semiTransparentMode_startIconsShown() {
        underTest.transitionTo(/* mode= */ MODE_SEMI_TRANSPARENT, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(true)

        assertThat(startIcons.capturedAlpha()).isEqualTo(1)
    }

    @Test
    fun onHeadsUpStateChanged_true_opaqueMode_startIconsUseResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(true)

        assertThat(startIcons.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    /** Regression test for b/291173113. */
    @Test
    fun onHeadsUpStateChanged_true_lightsOutMode_startIconsUseResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(true)

        assertThat(startIcons.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    @Test
    fun onHeadsUpStateChanged_false_semiTransparentMode_startIconsShown() {
        underTest.transitionTo(/* mode= */ MODE_SEMI_TRANSPARENT, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(1)
    }

    @Test
    fun onHeadsUpStateChanged_false_opaqueMode_startIconsUseResourceAlpha() {
        underTest.transitionTo(/* mode= */ MODE_OPAQUE, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(RESOURCE_ALPHA)
    }

    @Test
    fun onHeadsUpStateChanged_false_lightsOutMode_startIconsHidden() {
        underTest.transitionTo(/* mode= */ MODE_LIGHTS_OUT, /* animate= */ false)
        reset(startIcons)

        underTest.onHeadsUpStateChanged(false)

        assertThat(startIcons.capturedAlpha()).isEqualTo(0)
    }

    private fun View.capturedAlpha(): Float {
        val captor = argumentCaptor<Float>()
        verify(this).alpha = captor.capture()
        return captor.value
    }

    private companion object {
        const val RESOURCE_ALPHA = 0.34f
    }
}

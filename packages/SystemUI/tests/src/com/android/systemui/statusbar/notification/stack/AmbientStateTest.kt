/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val MAX_PULSE_HEIGHT = 100000f

@RunWith(AndroidTestingRunner::class)
@SmallTest
class AmbientStateTest : SysuiTestCase() {

    private val dumpManager = mock<DumpManager>()
    private val sectionProvider = StackScrollAlgorithm.SectionProvider { _, _ -> false }
    private val bypassController = StackScrollAlgorithm.BypassController { false }
    private val statusBarKeyguardViewManager = mock<StatusBarKeyguardViewManager>()
    private val largeScreenShadeInterpolator = mock<LargeScreenShadeInterpolator>()
    private val featureFlags = mock<FeatureFlags>()

    private lateinit var sut: AmbientState

    @Before
    fun setUp() {
        sut =
            AmbientState(
                context,
                dumpManager,
                sectionProvider,
                bypassController,
                statusBarKeyguardViewManager,
                largeScreenShadeInterpolator,
                featureFlags
            )
    }

    // region isDimmed
    @Test
    fun isDimmed_whenTrue_shouldReturnTrue() {
        sut.arrangeDimmed(true)

        assertThat(sut.isDimmed).isTrue()
    }

    @Test
    fun isDimmed_whenFalse_shouldReturnFalse() {
        sut.arrangeDimmed(false)

        assertThat(sut.isDimmed).isFalse()
    }

    @Test
    fun isDimmed_whenDozeAmountIsEmpty_shouldReturnTrue() {
        sut.arrangeDimmed(true)
        sut.dozeAmount = 0f

        assertThat(sut.isDimmed).isTrue()
    }

    @Test
    fun isDimmed_whenPulseExpandingIsFalse_shouldReturnTrue() {
        sut.arrangeDimmed(true)
        sut.arrangePulseExpanding(false)
        sut.dozeAmount = 1f // arrangePulseExpanding changes dozeAmount

        assertThat(sut.isDimmed).isTrue()
    }
    // endregion

    // region pulseHeight
    @Test
    fun pulseHeight_whenValueChanged_shouldCallListener() {
        var listenerCalledCount = 0
        sut.pulseHeight = MAX_PULSE_HEIGHT
        sut.setOnPulseHeightChangedListener { listenerCalledCount++ }

        sut.pulseHeight = 0f

        assertThat(listenerCalledCount).isEqualTo(1)
    }

    @Test
    fun pulseHeight_whenSetSameValue_shouldDoNothing() {
        var listenerCalledCount = 0
        sut.pulseHeight = MAX_PULSE_HEIGHT
        sut.setOnPulseHeightChangedListener { listenerCalledCount++ }

        sut.pulseHeight = MAX_PULSE_HEIGHT

        assertThat(listenerCalledCount).isEqualTo(0)
    }

    @Test
    fun pulseHeight_whenValueIsFull_shouldReturn0() {
        sut.pulseHeight = MAX_PULSE_HEIGHT

        assertThat(sut.pulseHeight).isEqualTo(0f)
    }

    @Test
    fun pulseHeight_whenValueIsNotFull_shouldReturnValue() {
        val expected = MAX_PULSE_HEIGHT - 0.1f
        sut.pulseHeight = expected

        assertThat(sut.pulseHeight).isEqualTo(expected)
    }
    // endregion

    // region statusBarState
    @Test
    fun statusBarState_whenPreviousStateIsNotKeyguardAndChange_shouldSetIsFlingRequiredToFalse() {
        sut.setStatusBarState(StatusBarState.SHADE)
        sut.isFlingRequiredAfterLockScreenSwipeUp = true

        sut.setStatusBarState(StatusBarState.KEYGUARD)

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isFalse()
    }

    @Test
    fun statusBarState_whenPreviousStateIsKeyguardAndChange_shouldDoNothing() {
        sut.setStatusBarState(StatusBarState.KEYGUARD)
        sut.isFlingRequiredAfterLockScreenSwipeUp = true

        sut.setStatusBarState(StatusBarState.SHADE)

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isTrue()
    }
    // endregion

    // region hideAmount
    @Test
    fun hideAmount_whenSetToFullValue_shouldReturnZeroFromPulseHeight() {
        sut.hideAmount = 0f
        sut.pulseHeight = 1f

        sut.hideAmount = 1f

        assertThat(sut.pulseHeight).isEqualTo(0f)
    }

    @Test
    fun hideAmount_whenSetToAnyNotFullValue_shouldDoNothing() {
        sut.hideAmount = 1f
        sut.pulseHeight = 1f

        sut.hideAmount = 0f

        assertThat(sut.pulseHeight).isEqualTo(1f)
    }
    // endregion

    // region dozeAmount
    @Test
    fun dozeAmount_whenDozeAmountIsSetToFullDozing_shouldReturnZeroFromPulseHeight() {
        sut.dozeAmount = 0f
        sut.pulseHeight = 1f

        sut.dozeAmount = 1f

        assertThat(sut.pulseHeight).isEqualTo(0f)
    }

    @Test
    fun dozeAmount_whenDozeAmountIsSetToFullAwake_shouldReturnZeroFromPulseHeight() {
        sut.dozeAmount = 1f
        sut.pulseHeight = 1f

        sut.dozeAmount = 0f

        assertThat(sut.pulseHeight).isEqualTo(0f)
    }

    @Test
    fun dozeAmount_whenDozeAmountIsSetAnyValueNotFullAwakeOrDozing_shouldDoNothing() {
        sut.dozeAmount = 1f
        sut.pulseHeight = 1f

        sut.dozeAmount = 0.5f

        assertThat(sut.pulseHeight).isEqualTo(1f)
    }
    // endregion

    // region trackedHeadsUpRow
    @Test
    fun trackedHeadsUpRow_whenIsAboveTheShelf_shouldReturnInstance() {
        sut.trackedHeadsUpRow = mock { whenever(isAboveShelf).thenReturn(true) }

        assertThat(sut.trackedHeadsUpRow).isNotNull()
    }

    @Test
    fun trackedHeadsUpRow_whenIsNotAboveTheShelf_shouldReturnNull() {
        sut.trackedHeadsUpRow = mock { whenever(isAboveShelf).thenReturn(false) }

        assertThat(sut.trackedHeadsUpRow).isNull()
    }
    // endregion

    // region isSwipingUp
    @Test
    fun isSwipingUp_whenValueChangedToTrue_shouldRequireFling() {
        sut.isSwipingUp = false
        sut.isFlingRequiredAfterLockScreenSwipeUp = false

        sut.isSwipingUp = true

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isFalse()
    }

    @Test
    fun isSwipingUp_whenValueChangedToFalse_shouldRequireFling() {
        sut.isSwipingUp = true
        sut.isFlingRequiredAfterLockScreenSwipeUp = false

        sut.isSwipingUp = false

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isTrue()
    }
    // endregion

    // region isFlinging
    @Test
    fun isFlinging_shouldNotNeedFling() {
        sut.arrangeFlinging(true)

        sut.setFlinging(false)

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isFalse()
    }

    @Test
    fun isFlinging_whenNotOnLockScreen_shouldDoNothing() {
        sut.arrangeFlinging(true)
        sut.setStatusBarState(StatusBarState.SHADE)
        sut.isFlingRequiredAfterLockScreenSwipeUp = true

        sut.setFlinging(false)

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isTrue()
    }

    @Test
    fun isFlinging_whenValueChangedToTrue_shouldDoNothing() {
        sut.arrangeFlinging(false)

        sut.setFlinging(true)

        assertThat(sut.isFlingRequiredAfterLockScreenSwipeUp).isTrue()
    }
    // endregion

    // region scrollY
    @Test
    fun scrollY_shouldSetValueGreaterThanZero() {
        sut.scrollY = 0

        sut.scrollY = 1

        assertThat(sut.scrollY).isEqualTo(1)
    }

    @Test
    fun scrollY_shouldNotSetValueLessThanZero() {
        sut.scrollY = 0

        sut.scrollY = -1

        assertThat(sut.scrollY).isEqualTo(0)
    }
    // endregion

    // region setOverScrollAmount
    fun setOverScrollAmount_shouldSetValueOnTop() {
        sut.setOverScrollAmount(/* amount = */ 10f, /* onTop = */ true)

        val resultOnTop = sut.getOverScrollAmount(/* top = */ true)
        val resultOnBottom = sut.getOverScrollAmount(/* top = */ false)

        assertThat(resultOnTop).isEqualTo(10f)
        assertThat(resultOnBottom).isEqualTo(0f)
    }

    fun setOverScrollAmount_shouldSetValueOnBottom() {
        sut.setOverScrollAmount(/* amount = */ 10f, /* onTop = */ false)

        val resultOnTop = sut.getOverScrollAmount(/* top */ true)
        val resultOnBottom = sut.getOverScrollAmount(/* top */ false)

        assertThat(resultOnTop).isEqualTo(0f)
        assertThat(resultOnBottom).isEqualTo(10f)
    }
    // endregion

    // region IsPulseExpanding
    @Test
    fun isPulseExpanding_shouldReturnTrue() {
        sut.arrangePulseExpanding(true)

        assertThat(sut.isPulseExpanding).isTrue()
    }

    @Test
    fun isPulseExpanding_whenPulseHeightIsMax_shouldReturnFalse() {
        sut.arrangePulseExpanding(true)
        sut.pulseHeight = MAX_PULSE_HEIGHT

        assertThat(sut.isPulseExpanding).isFalse()
    }

    @Test
    fun isPulseExpanding_whenDozeAmountIsZero_shouldReturnFalse() {
        sut.arrangePulseExpanding(true)
        sut.dozeAmount = 0f

        assertThat(sut.isPulseExpanding).isFalse()
    }

    @Test
    fun isPulseExpanding_whenHideAmountIsFull_shouldReturnFalse() {
        sut.arrangePulseExpanding(true)
        sut.hideAmount = 1f

        assertThat(sut.isPulseExpanding).isFalse()
    }
    // endregion

    // region isOnKeyguard
    @Test
    fun isOnKeyguard_whenStatusBarStateIsKeyguard_shouldReturnTrue() {
        sut.setStatusBarState(StatusBarState.KEYGUARD)

        assertThat(sut.isOnKeyguard).isTrue()
    }

    @Test
    fun isOnKeyguard_whenStatusBarStateIsNotKeyguard_shouldReturnFalse() {
        sut.setStatusBarState(StatusBarState.SHADE)

        assertThat(sut.isOnKeyguard).isFalse()
    }
    // endregion

    // region mIsClosing
    @Test
    fun isClosing_whenShadeClosing_shouldReturnTrue() {
        sut.setIsClosing(true)

        assertThat(sut.isClosing).isTrue()
    }

    @Test
    fun isClosing_whenShadeFinishClosing_shouldReturnFalse() {
        sut.setIsClosing(false)

        assertThat(sut.isClosing).isFalse()
    }
    // endregion
}

// region Arrange helper methods.
private fun AmbientState.arrangeDimmed(value: Boolean) {
    isDimmed = value
    dozeAmount = if (value) 0f else 1f
    arrangePulseExpanding(!value)
}

private fun AmbientState.arrangePulseExpanding(value: Boolean) {
    if (value) {
        dozeAmount = 1f
        hideAmount = 0f
        pulseHeight = 0f
    } else {
        dozeAmount = 0f
        hideAmount = 1f
        pulseHeight = MAX_PULSE_HEIGHT
    }
}

private fun AmbientState.arrangeFlinging(value: Boolean) {
    setStatusBarState(StatusBarState.KEYGUARD)
    setFlinging(value)
    isFlingRequiredAfterLockScreenSwipeUp = true
}
// endregion

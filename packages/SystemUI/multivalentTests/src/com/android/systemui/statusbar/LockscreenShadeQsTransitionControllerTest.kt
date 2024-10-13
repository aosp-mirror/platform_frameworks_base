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
 * limitations under the License.
 */

package com.android.systemui.statusbar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenShadeQsTransitionControllerTest : SysuiTestCase() {

    private val configurationController = FakeConfigurationController()

    @get:Rule val expect: Expect = Expect.create()

    @Mock private lateinit var dumpManager: DumpManager
    private var qS: QS? = null

    private lateinit var controller: LockscreenShadeQsTransitionController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        qS = mock()

        setTransitionDistance(TRANSITION_DISTANCE)
        setTransitionDelay(TRANSITION_DELAY)
        setSquishTransitionDistance(SQUISH_TRANSITION_DISTANCE)
        setSquishStartFraction(SQUISH_START_FRACTION)

        controller =
            LockscreenShadeQsTransitionController(
                context,
                configurationController,
                dumpManager,
                qsProvider = { qS },
                ResourcesSplitShadeStateController()
            )
    }

    @Test
    fun qsTransitionFraction_byDefault_returns0() {
        assertThat(controller.qsTransitionFraction).isZero()
    }

    @Test
    fun qsTransitionFraction_noStartDelay_returnsBasedOnTransitionDistance() {
        setTransitionDelay(0)
        setTransitionDistance(100)

        controller.dragDownAmount = 25f
        expect.that(controller.qsTransitionFraction).isEqualTo(0.25f)

        controller.dragDownAmount = 50f
        expect.that(controller.qsTransitionFraction).isEqualTo(0.5f)

        controller.dragDownAmount = 75f
        expect.that(controller.qsTransitionFraction).isEqualTo(0.75f)

        controller.dragDownAmount = 100f
        expect.that(controller.qsTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsTransitionFraction_noStartDelay_returnsValuesBetween0and1() {
        setTransitionDelay(0)
        setTransitionDistance(100)

        controller.dragDownAmount = -500f
        expect.that(controller.qsTransitionFraction).isEqualTo(0f)

        controller.dragDownAmount = 500f
        expect.that(controller.qsTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsTransitionFraction_withStartDelay_returnsBasedOnTransitionDistanceAndDelay() {
        setTransitionDelay(10)
        setTransitionDistance(100)

        controller.dragDownAmount = 0f
        expect.that(controller.qsTransitionFraction).isEqualTo(0f)

        controller.dragDownAmount = 10f
        expect.that(controller.qsTransitionFraction).isEqualTo(0f)

        controller.dragDownAmount = 25f
        expect.that(controller.qsTransitionFraction).isEqualTo(0.15f)

        controller.dragDownAmount = 100f
        expect.that(controller.qsTransitionFraction).isEqualTo(0.9f)

        controller.dragDownAmount = 110f
        expect.that(controller.qsTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsTransitionFraction_withStartDelay_returnsValuesBetween0and1() {
        setTransitionDelay(10)
        setTransitionDistance(100)

        controller.dragDownAmount = -500f
        expect.that(controller.qsTransitionFraction).isEqualTo(0f)

        controller.dragDownAmount = 500f
        expect.that(controller.qsTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsSquishTransitionFraction_byDefault_returnsValueSetFromResource() {
        assertThat(controller.qsSquishTransitionFraction).isEqualTo(SQUISH_START_FRACTION)
    }

    @Test
    fun qsSquishTransitionFraction_noStartDelay_startFraction0_returnsBasedOnTransitionDistance() {
        setTransitionDelay(0)
        setSquishStartFraction(0f)
        setSquishTransitionDistance(1000)

        controller.dragDownAmount = 250f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.25f)

        controller.dragDownAmount = 500f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.5f)

        controller.dragDownAmount = 750f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.75f)

        controller.dragDownAmount = 1000f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsSquishTransitionFraction_startDelay_startFraction0_basedOnTransitionDistanceAndDelay() {
        setTransitionDelay(100)
        setSquishStartFraction(0f)
        setSquishTransitionDistance(1000)

        controller.dragDownAmount = 250f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.15f)

        controller.dragDownAmount = 500f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.4f)

        controller.dragDownAmount = 750f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.65f)

        controller.dragDownAmount = 1000f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.9f)

        controller.dragDownAmount = 1100f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsSquishTransitionFraction_noStartDelay_startFractionSet_returnsBasedOnStartAndDistance() {
        setTransitionDelay(0)
        setSquishStartFraction(0.5f)
        setSquishTransitionDistance(1000)

        controller.dragDownAmount = 0f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.5f)

        controller.dragDownAmount = 500f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.75f)

        controller.dragDownAmount = 1000f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun qsSquishTransitionFraction_startDelay_startFractionSet_basedOnStartAndDistanceAndDelay() {
        setTransitionDelay(10)
        setSquishStartFraction(0.5f)
        setSquishTransitionDistance(100)

        controller.dragDownAmount = 0f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.5f)

        controller.dragDownAmount = 50f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.7f)

        controller.dragDownAmount = 100f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(0.95f)

        controller.dragDownAmount = 110f
        expect.that(controller.qsSquishTransitionFraction).isEqualTo(1f)
    }

    @Test
    fun onDragDownAmountChanged_setsValuesOnQS() {
        val rawDragAmount = 200f

        controller.dragDownAmount = rawDragAmount

        verify(qS!!)
            .setTransitionToFullShadeProgress(
                /* isTransitioningToFullShade= */ true,
                /* transitionFraction= */ controller.qsTransitionFraction,
                /* squishinessFraction= */ controller.qsSquishTransitionFraction
            )
    }

    @Test
    fun nullQS_onDragAmountChanged_doesNotCrash() {
        qS = null

        val rawDragAmount = 200f

        controller.dragDownAmount = rawDragAmount
    }

    private fun setTransitionDistance(value: Int) {
        overrideResource(R.dimen.lockscreen_shade_qs_transition_distance, value)
        configurationController.notifyConfigurationChanged()
    }

    private fun setTransitionDelay(value: Int) {
        overrideResource(R.dimen.lockscreen_shade_qs_transition_delay, value)
        configurationController.notifyConfigurationChanged()
    }

    private fun setSquishTransitionDistance(value: Int) {
        overrideResource(R.dimen.lockscreen_shade_qs_squish_transition_distance, value)
        configurationController.notifyConfigurationChanged()
    }

    private fun setSquishStartFraction(value: Float) {
        overrideResource(R.dimen.lockscreen_shade_qs_squish_start_fraction, value)
        configurationController.notifyConfigurationChanged()
    }

    companion object {
        private const val TRANSITION_DELAY = 123
        private const val TRANSITION_DISTANCE = 321
        private const val SQUISH_START_FRACTION = 0.123f
        private const val SQUISH_TRANSITION_DISTANCE = 456
    }
}

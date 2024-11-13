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
package com.android.keyguard

import android.animation.Animator
import android.transition.TransitionValues
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardStatusViewController.SplitShadeTransitionAdapter
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class SplitShadeTransitionAdapterTest : SysuiTestCase() {

    @Mock private lateinit var KeyguardClockSwitchController: KeyguardClockSwitchController

    private lateinit var adapter: SplitShadeTransitionAdapter

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        adapter = SplitShadeTransitionAdapter(KeyguardClockSwitchController)
    }

    @Test
    fun createAnimator_nullStartValues_returnsNull() {
        val endValues = createEndValues()

        val animator = adapter.createAnimator(startValues = null, endValues = endValues)

        assertThat(animator).isNull()
    }

    @Test
    fun createAnimator_nullEndValues_returnsNull() {
        val animator = adapter.createAnimator(startValues = createStartValues(), endValues = null)

        assertThat(animator).isNull()
    }

    @Test
    fun createAnimator_nonNullStartAndEndValues_returnsAnimator() {
        val animator =
            adapter.createAnimator(startValues = createStartValues(), endValues = createEndValues())

        assertThat(animator).isNotNull()
    }

    private fun createStartValues() =
        TransitionValues().also { values ->
            values.view = View(context)
            adapter.captureStartValues(values)
        }

    private fun createEndValues() =
        TransitionValues().also { values ->
            values.view = View(context)
            adapter.captureEndValues(values)
        }
}

private fun SplitShadeTransitionAdapter.createAnimator(
    startValues: TransitionValues?,
    endValues: TransitionValues?
): Animator? {
    return createAnimator(/* sceneRoot= */ mock(), startValues, endValues)
}

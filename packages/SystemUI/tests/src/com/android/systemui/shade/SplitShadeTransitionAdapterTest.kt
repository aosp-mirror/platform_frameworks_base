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

import android.animation.Animator
import android.testing.AndroidTestingRunner
import android.transition.TransitionValues
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardStatusViewController
import com.android.systemui.SysuiTestCase
import com.android.systemui.shade.NotificationPanelViewController.SplitShadeTransitionAdapter
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SplitShadeTransitionAdapterTest : SysuiTestCase() {

    @Mock private lateinit var keyguardStatusViewController: KeyguardStatusViewController

    private lateinit var adapter: SplitShadeTransitionAdapter

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        adapter = SplitShadeTransitionAdapter(keyguardStatusViewController)
    }

    @Test
    fun createAnimator_nullStartValues_returnsNull() {
        val animator = adapter.createAnimator(startValues = null, endValues = TransitionValues())

        assertThat(animator).isNull()
    }

    @Test
    fun createAnimator_nullEndValues_returnsNull() {
        val animator = adapter.createAnimator(startValues = TransitionValues(), endValues = null)

        assertThat(animator).isNull()
    }

    @Test
    fun createAnimator_nonNullStartAndEndValues_returnsAnimator() {
        val animator =
            adapter.createAnimator(startValues = TransitionValues(), endValues = TransitionValues())

        assertThat(animator).isNotNull()
    }
}

private fun SplitShadeTransitionAdapter.createAnimator(
    startValues: TransitionValues?,
    endValues: TransitionValues?
): Animator? {
    return createAnimator(/* sceneRoot= */ null, startValues, endValues)
}

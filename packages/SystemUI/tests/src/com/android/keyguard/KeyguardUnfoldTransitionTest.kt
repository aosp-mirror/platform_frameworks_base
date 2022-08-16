/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter fn.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardUnfoldTransitionTest : SysuiTestCase() {

    @Mock private lateinit var progressProvider: NaturalRotationUnfoldProgressProvider

    @Captor private lateinit var progressListenerCaptor: ArgumentCaptor<TransitionProgressListener>

    @Mock private lateinit var parent: ViewGroup

    private lateinit var keyguardUnfoldTransition: KeyguardUnfoldTransition
    private lateinit var progressListener: TransitionProgressListener
    private var xTranslationMax = 0f

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        xTranslationMax =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_unfold_translation_x).toFloat()

        keyguardUnfoldTransition = KeyguardUnfoldTransition(context, progressProvider)

        keyguardUnfoldTransition.setup(parent)
        keyguardUnfoldTransition.statusViewCentered = false

        verify(progressProvider).addCallback(capture(progressListenerCaptor))
        progressListener = progressListenerCaptor.value
    }

    @Test
    fun onTransition_centeredViewDoesNotMove() {
        keyguardUnfoldTransition.statusViewCentered = true

        val view = View(context)
        `when`(parent.findViewById<View>(R.id.lockscreen_clock_view_large)).thenReturn(view)

        progressListener.onTransitionStarted()
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0.5f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionFinished()
        assertThat(view.translationX).isZero()
    }
}

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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter fn.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardUnfoldTransitionTest : SysuiTestCase() {

    private val kosmos = Kosmos()

    private val progressProvider: FakeUnfoldTransitionProvider =
        kosmos.fakeUnfoldTransitionProgressProvider

    @Mock
    private lateinit var keyguardRootView: KeyguardRootView

    @Mock
    private lateinit var notificationShadeWindowView: NotificationShadeWindowView

    @Mock private lateinit var statusBarStateController: StatusBarStateController

    private lateinit var underTest: KeyguardUnfoldTransition
    private lateinit var progressListener: TransitionProgressListener
    private var xTranslationMax = 0f

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        xTranslationMax =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_unfold_translation_x).toFloat()

        underTest = KeyguardUnfoldTransition(
            context, keyguardRootView, notificationShadeWindowView,
            statusBarStateController, progressProvider
        )

        underTest.setup()
        underTest.statusViewCentered = false

        progressListener = progressProvider
    }

    @Test
    fun onTransition_centeredViewDoesNotMove() {
        whenever(statusBarStateController.getState()).thenReturn(KEYGUARD)
        underTest.statusViewCentered = true

        val view = View(context)
        whenever(keyguardRootView.findViewById<View>(R.id.lockscreen_clock_view_large)).thenReturn(
            view
        )

        progressListener.onTransitionStarted()
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0.5f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInShadeState_viewDoesNotMove() {
        whenever(statusBarStateController.getState()).thenReturn(SHADE)

        val view = View(context)
        whenever(keyguardRootView.findViewById<View>(R.id.lockscreen_clock_view_large)).thenReturn(
            view
        )

        progressListener.onTransitionStarted()
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0.5f)
        assertThat(view.translationX).isZero()

        progressListener.onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInKeyguardState_viewDoesMove() {
        whenever(statusBarStateController.getState()).thenReturn(KEYGUARD)

        val view = View(context)
        whenever(
            notificationShadeWindowView
                .findViewById<View>(R.id.lockscreen_clock_view_large)
        ).thenReturn(view)

        progressListener.onTransitionStarted()
        assertThat(view.translationX).isZero()

        progressListener.onTransitionProgress(0f)
        assertThat(view.translationX).isEqualTo(xTranslationMax)

        progressListener.onTransitionProgress(0.5f)
        assertThat(view.translationX).isEqualTo(0.5f * xTranslationMax)

        progressListener.onTransitionFinished()
        assertThat(view.translationX).isZero()
    }
}

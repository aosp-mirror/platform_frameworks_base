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

package com.android.systemui.shade

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter fn.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationPanelUnfoldAnimationControllerTest : SysuiTestCase() {

    @Mock private lateinit var progressProvider: NaturalRotationUnfoldProgressProvider

    @Captor private lateinit var progressListenerCaptor: ArgumentCaptor<TransitionProgressListener>

    @Mock private lateinit var parent: ViewGroup

    @Mock private lateinit var splitShadeStatusBar: ViewGroup

    @Mock private lateinit var statusBarStateController: StatusBarStateController

    private lateinit var underTest: NotificationPanelUnfoldAnimationController
    private lateinit var progressListeners: List<TransitionProgressListener>
    private var xTranslationMax = 0f

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        xTranslationMax =
            context.resources.getDimensionPixelSize(R.dimen.notification_side_paddings).toFloat()

        underTest =
            NotificationPanelUnfoldAnimationController(
                context,
                statusBarStateController,
                progressProvider
            )
        whenever(parent.findViewById<ViewGroup>(R.id.split_shade_status_bar)).thenReturn(
            splitShadeStatusBar
        )
        underTest.setup(parent)

        verify(progressProvider, atLeastOnce()).addCallback(capture(progressListenerCaptor))
        progressListeners = progressListenerCaptor.allValues
    }

    @Test
    fun whenInKeyguardState_viewDoesNotMove() {
        whenever(statusBarStateController.getState()).thenReturn(KEYGUARD)

        val view = View(context)
        whenever(parent.findViewById<View>(R.id.quick_settings_panel)).thenReturn(view)

        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isZero()

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isZero()

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInShadeState_viewDoesMove() {
        whenever(statusBarStateController.getState()).thenReturn(SHADE)

        val view = View(context)
        whenever(parent.findViewById<View>(R.id.quick_settings_panel)).thenReturn(view)

        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isEqualTo(xTranslationMax)

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isEqualTo(0.5f * xTranslationMax)

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInShadeLockedState_viewDoesMove() {
        whenever(statusBarStateController.getState()).thenReturn(SHADE_LOCKED)

        val view = View(context)
        whenever(parent.findViewById<View>(R.id.quick_settings_panel)).thenReturn(view)

        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isEqualTo(xTranslationMax)

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isEqualTo(0.5f * xTranslationMax)

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInKeyguardState_statusBarViewDoesNotMove() {
        whenever(statusBarStateController.getState()).thenReturn(KEYGUARD)

        val view = View(context)
        whenever(splitShadeStatusBar.findViewById<View>(R.id.date)).thenReturn(view)

        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isZero()

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isZero()

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInShadeState_statusBarViewDoesMove() {
        whenever(statusBarStateController.getState()).thenReturn(SHADE)

        val view = View(context)
        whenever(splitShadeStatusBar.findViewById<View>(R.id.date)).thenReturn(view)

        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isEqualTo(xTranslationMax)

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isEqualTo(0.5f * xTranslationMax)

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    @Test
    fun whenInShadeLockedState_statusBarViewDoesMove() {
        whenever(statusBarStateController.getState()).thenReturn(SHADE_LOCKED)

        val view = View(context)
        whenever(splitShadeStatusBar.findViewById<View>(R.id.date)).thenReturn(view)
        onTransitionStarted()
        assertThat(view.translationX).isZero()

        onTransitionProgress(0f)
        assertThat(view.translationX).isEqualTo(xTranslationMax)

        onTransitionProgress(0.5f)
        assertThat(view.translationX).isEqualTo(0.5f * xTranslationMax)

        onTransitionFinished()
        assertThat(view.translationX).isZero()
    }

    private fun onTransitionStarted() {
        progressListeners.forEach { it.onTransitionStarted() }
    }

    private fun onTransitionProgress(progress: Float) {
        progressListeners.forEach { it.onTransitionProgress(progress) }
    }

    private fun onTransitionFinished() {
        progressListeners.forEach { it.onTransitionFinished() }
    }

}

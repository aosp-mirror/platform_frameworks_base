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

package com.android.systemui.statusbar.phone

import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
class PhoneStatusBarViewTest : SysuiTestCase() {

    @Mock
    private lateinit var panelViewController: PanelViewController
    @Mock
    private lateinit var panelView: ViewGroup
    @Mock
    private lateinit var scrimController: ScrimController

    private lateinit var view: PhoneStatusBarView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        // TODO(b/197137564): Setting up a panel view and its controller feels unnecessary when
        //   testing just [PhoneStatusBarView].
        `when`(panelViewController.view).thenReturn(panelView)

        view = PhoneStatusBarView(mContext, null)
        view.setPanel(panelViewController)
        view.setScrimController(scrimController)
    }

    @Test
    fun panelEnabled_providerReturnsTrue_returnsTrue() {
        view.setPanelEnabledProvider { true }

        assertThat(view.panelEnabled()).isTrue()
    }

    @Test
    fun panelEnabled_providerReturnsFalse_returnsFalse() {
        view.setPanelEnabledProvider { false }

        assertThat(view.panelEnabled()).isFalse()
    }

    @Test
    fun panelEnabled_noProvider_noCrash() {
        view.panelEnabled()
        // No assert needed, just testing no crash
    }

    @Test
    fun panelExpansionChanged_fracZero_stateChangeListenerNotified() {
        val listener = TestStateChangedListener()
        view.setPanelExpansionStateChangedListener(listener)

        view.panelExpansionChanged(0f, false)

        assertThat(listener.stateChangeCalled).isTrue()
    }

    @Test
    fun panelExpansionChanged_fracOne_stateChangeListenerNotified() {
        val listener = TestStateChangedListener()
        view.setPanelExpansionStateChangedListener(listener)

        view.panelExpansionChanged(1f, false)

        assertThat(listener.stateChangeCalled).isTrue()
    }

    @Test
    fun panelExpansionChanged_fracHalf_stateChangeListenerNotNotified() {
        val listener = TestStateChangedListener()
        view.setPanelExpansionStateChangedListener(listener)

        view.panelExpansionChanged(0.5f, false)

        assertThat(listener.stateChangeCalled).isFalse()
    }

    @Test
    fun panelExpansionChanged_noStateChangeListener_noCrash() {
        view.panelExpansionChanged(1f, false)
        // No assert needed, just testing no crash
    }

    private class TestStateChangedListener : PhoneStatusBarView.PanelExpansionStateChangedListener {
        var stateChangeCalled: Boolean = false

        override fun onPanelExpansionStateChanged() {
            stateChangeCalled = true
        }
    }
}

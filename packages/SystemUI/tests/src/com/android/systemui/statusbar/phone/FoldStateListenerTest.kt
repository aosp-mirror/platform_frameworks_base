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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.foldedDeviceStateList
import com.android.systemui.halfFoldedDeviceState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.statusbar.phone.FoldStateListener.OnFoldStateChangeListener
import com.android.systemui.unfoldedDeviceState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@RunWith(AndroidJUnit4::class)
@SmallTest
class FoldStateListenerTest : SysuiTestCase() {

    @Mock private lateinit var listener: OnFoldStateChangeListener
    private lateinit var sut: FoldStateListener

    @Before
    fun setUp() {
        initMocks(this)
        setFoldedStates(DEVICE_STATE_FOLDED.identifier)
        setGoToSleepStates(DEVICE_STATE_FOLDED.identifier)
        sut = FoldStateListener(mContext, listener)
    }

    @Test
    fun onStateChanged_stateFolded_notifiesWithFoldedAndGoingToSleep() {
        sut.onDeviceStateChanged(DEVICE_STATE_FOLDED)

        verify(listener).onFoldStateChanged(FOLDED, WILL_GO_TO_SLEEP)
    }

    @Test
    fun onStateChanged_stateHalfFolded_notifiesWithNotFoldedAndNotGoingToSleep() {
        sut.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)

        verify(listener).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
    }

    @Test
    fun onStateChanged_stateUnfolded_notifiesWithNotFoldedAndNotGoingToSleep() {
        sut.onDeviceStateChanged(DEVICE_STATE_UNFOLDED)

        verify(listener).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
    }

    @Test
    fun onStateChanged_stateUnfoldedThenHalfFolded_notifiesOnce() {
        sut.onDeviceStateChanged(DEVICE_STATE_UNFOLDED)
        sut.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)

        verify(listener, times(1)).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
    }

    @Test
    fun onStateChanged_stateHalfFoldedThenUnfolded_notifiesOnce() {
        sut.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)
        sut.onDeviceStateChanged(DEVICE_STATE_UNFOLDED)

        verify(listener, times(1)).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
    }

    @Test
    fun onStateChanged_stateHalfFoldedThenFolded_notifiesTwice() {
        sut.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)
        sut.onDeviceStateChanged(DEVICE_STATE_FOLDED)

        val inOrder = Mockito.inOrder(listener)
        inOrder.verify(listener).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
        inOrder.verify(listener).onFoldStateChanged(FOLDED, WILL_GO_TO_SLEEP)
    }

    @Test
    fun onStateChanged_stateFoldedThenHalfFolded_notifiesTwice() {
        sut.onDeviceStateChanged(DEVICE_STATE_FOLDED)
        sut.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)

        val inOrder = Mockito.inOrder(listener)
        inOrder.verify(listener).onFoldStateChanged(FOLDED, WILL_GO_TO_SLEEP)
        inOrder.verify(listener).onFoldStateChanged(NOT_FOLDED, WILL_NOT_SLEEP)
    }

    private fun setGoToSleepStates(vararg states: Int) {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_deviceStatesOnWhichToSleep,
            states
        )
    }

    private fun setFoldedStates(vararg states: Int) {
        mContext.orCreateTestableResources.addOverride(R.array.config_foldedDeviceStates, states)
    }

    companion object {
        private val DEVICE_STATE_FOLDED = Kosmos().foldedDeviceStateList.first()
        private val DEVICE_STATE_HALF_FOLDED = Kosmos().halfFoldedDeviceState
        private val DEVICE_STATE_UNFOLDED = Kosmos().unfoldedDeviceState

        private const val FOLDED = true
        private const val NOT_FOLDED = false

        private const val WILL_GO_TO_SLEEP = true
        private const val WILL_NOT_SLEEP = false
    }
}

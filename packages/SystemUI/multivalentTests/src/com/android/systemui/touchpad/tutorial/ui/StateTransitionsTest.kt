/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.touchpad.tutorial.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Error
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgress
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgressAfterError
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NotStarted
import com.android.systemui.touchpad.tutorial.ui.composable.toGestureUiState
import com.android.systemui.touchpad.tutorial.ui.composable.toTutorialActionState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class StateTransitionsTest : SysuiTestCase() {

    companion object {
        private const val START_MARKER = "startMarker"
        private const val END_MARKER = "endMarker"
        private const val SUCCESS_ANIMATION = 0
    }

    // needed to simulate caching last state as it's used to create new state
    private var lastState: TutorialActionState = NotStarted

    private fun GestureState.toTutorialActionState(): TutorialActionState {
        val newState =
            this.toGestureUiState(
                    progressStartMarker = START_MARKER,
                    progressEndMarker = END_MARKER,
                    successAnimation = SUCCESS_ANIMATION,
                )
                .toTutorialActionState(lastState)
        lastState = newState
        return lastState
    }

    @Test
    fun gestureStateProducesEquivalentTutorialActionStateInHappyPath() {
        val happyPath =
            listOf(
                GestureState.NotStarted,
                GestureState.InProgress(0f),
                GestureState.InProgress(0.5f),
                GestureState.InProgress(1f),
                GestureState.Finished,
            )

        val resultingStates = mutableListOf<TutorialActionState>()
        happyPath.forEach { resultingStates.add(it.toTutorialActionState()) }

        assertThat(resultingStates)
            .containsExactly(
                NotStarted,
                InProgress(0f, START_MARKER, END_MARKER),
                InProgress(0.5f, START_MARKER, END_MARKER),
                InProgress(1f, START_MARKER, END_MARKER),
                Finished(SUCCESS_ANIMATION),
            )
            .inOrder()
    }

    @Test
    fun gestureStateProducesEquivalentTutorialActionStateInErrorPath() {
        val errorPath =
            listOf(
                GestureState.NotStarted,
                GestureState.InProgress(0f),
                GestureState.Error,
                GestureState.InProgress(0.5f),
                GestureState.InProgress(1f),
                GestureState.Finished,
            )

        val resultingStates = mutableListOf<TutorialActionState>()
        errorPath.forEach { resultingStates.add(it.toTutorialActionState()) }

        assertThat(resultingStates)
            .containsExactly(
                NotStarted,
                InProgress(0f, START_MARKER, END_MARKER),
                Error,
                InProgressAfterError(InProgress(0.5f, START_MARKER, END_MARKER)),
                InProgressAfterError(InProgress(1f, START_MARKER, END_MARKER)),
                Finished(SUCCESS_ANIMATION),
            )
            .inOrder()
    }
}

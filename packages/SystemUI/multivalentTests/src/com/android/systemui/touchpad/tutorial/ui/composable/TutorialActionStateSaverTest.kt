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

package com.android.systemui.touchpad.tutorial.ui.composable

import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Error
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.Finished
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgress
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.InProgressAfterError
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState.NotStarted
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TutorialActionStateSaverTest : SysuiTestCase() {

    private val saver = TutorialActionState.stateSaver()
    private val saverScope: SaverScope =
        object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }

    @Test
    fun inProgressIsRestoredToNotStartedState() {
        assertRestoredState(
            savedState = InProgress(progress = 0f),
            expectedRestoredState = NotStarted,
        )
    }

    @Test
    fun inProgressErrorIsRestoredToErrorState() {
        assertRestoredState(
            savedState = InProgressAfterError(InProgress(progress = 0f)),
            expectedRestoredState = Error,
        )
    }

    @Test
    fun otherStatesAreRestoredToTheSameState() {
        assertRestoredState(savedState = NotStarted, expectedRestoredState = NotStarted)
        assertRestoredState(savedState = Error, expectedRestoredState = Error)
        assertRestoredState(
            savedState = Finished(successAnimation = R.raw.trackpad_home_success),
            expectedRestoredState = Finished(successAnimation = R.raw.trackpad_home_success),
        )
    }

    private fun assertRestoredState(
        savedState: TutorialActionState,
        expectedRestoredState: TutorialActionState,
    ) {
        val savedValue = with(saver) { saverScope.save(savedState) }
        assertThat(saver.restore(savedValue!!)).isEqualTo(expectedRestoredState)
    }
}

/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class StatusBarStateEventTest : SysuiTestCase() {

    @Test
    fun testFromState() {
        val events = listOf(
                StatusBarStateEvent.STATUS_BAR_STATE_SHADE,
                StatusBarStateEvent.STATUS_BAR_STATE_SHADE_LOCKED,
                StatusBarStateEvent.STATUS_BAR_STATE_KEYGUARD,
                StatusBarStateEvent.STATUS_BAR_STATE_UNKNOWN
        )
        val states = listOf(
                StatusBarState.SHADE,
                StatusBarState.SHADE_LOCKED,
                StatusBarState.KEYGUARD,
                -1
        )
        events.zip(states).forEach { (event, state) ->
            assertEquals(event, StatusBarStateEvent.fromState(state))
        }
    }
}

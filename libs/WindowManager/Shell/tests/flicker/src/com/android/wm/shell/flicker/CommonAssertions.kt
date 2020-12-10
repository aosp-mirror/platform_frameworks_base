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

package com.android.wm.shell.flicker

import com.android.server.wm.flicker.dsl.EventLogAssertion
import com.android.server.wm.flicker.dsl.LayersAssertion

@JvmOverloads
fun LayersAssertion.appPairsDividerIsVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("appPairsDividerIsVisible", bugId, enabled) {
        this.showsLayer(FlickerTestBase.SPLIT_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.appPairsDividerIsInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("appPairsDividerIsInVisible", bugId, enabled) {
        this.hasNotLayer(FlickerTestBase.SPLIT_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerIsVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("dockedStackDividerIsVisible", bugId, enabled) {
        this.showsLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
    }
}

@JvmOverloads
fun LayersAssertion.dockedStackDividerIsInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    end("dockedStackDividerIsInvisible", bugId, enabled) {
        this.hasNotLayer(FlickerTestBase.DOCKED_STACK_DIVIDER)
    }
}

fun EventLogAssertion.focusChanges(
    vararg windows: String,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all(enabled = enabled, bugId = bugId) {
        this.focusChanges(windows)
    }
}

fun EventLogAssertion.focusDoesNotChange(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all(enabled = enabled, bugId = bugId) {
        this.focusDoesNotChange()
    }
}

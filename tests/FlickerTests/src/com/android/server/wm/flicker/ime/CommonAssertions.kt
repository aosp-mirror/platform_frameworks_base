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

@file:JvmName("CommonAssertions")
package com.android.server.wm.flicker.ime

import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.traces.parser.windowmanager.WindowManagerStateHelper

fun FlickerTestParameter.imeLayerBecomesVisible() {
    assertLayers {
        this.isInvisible(WindowManagerStateHelper.IME_COMPONENT)
            .then()
            .isVisible(WindowManagerStateHelper.IME_COMPONENT)
    }
}

fun FlickerTestParameter.imeLayerBecomesInvisible() {
    assertLayers {
        this.isVisible(WindowManagerStateHelper.IME_COMPONENT)
            .then()
            .isInvisible(WindowManagerStateHelper.IME_COMPONENT)
    }
}

fun FlickerTestParameter.imeWindowIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertWm {
            this.isNonAppWindowVisible(WindowManagerStateHelper.IME_COMPONENT)
                .then()
                .isNonAppWindowInvisible(WindowManagerStateHelper.IME_COMPONENT)
                .then()
                .isNonAppWindowVisible(WindowManagerStateHelper.IME_COMPONENT)
        }
    } else {
        assertWm {
            this.isNonAppWindowVisible(WindowManagerStateHelper.IME_COMPONENT)
        }
    }
}

fun FlickerTestParameter.imeWindowBecomesVisible() {
    assertWm {
        this.isNonAppWindowInvisible(WindowManagerStateHelper.IME_COMPONENT)
            .then()
            .isNonAppWindowVisible(WindowManagerStateHelper.IME_COMPONENT)
    }
}

fun FlickerTestParameter.imeWindowBecomesInvisible() {
    assertWm {
        this.isNonAppWindowVisible(WindowManagerStateHelper.IME_COMPONENT)
            .then()
            .isNonAppWindowInvisible(WindowManagerStateHelper.IME_COMPONENT)
    }
}

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

import android.tools.common.traces.component.ComponentNameMatcher
import android.tools.device.flicker.legacy.LegacyFlickerTest

fun LegacyFlickerTest.imeLayerBecomesVisible() {
    assertLayers {
        this.isInvisible(ComponentNameMatcher.IME).then().isVisible(ComponentNameMatcher.IME)
    }
}

fun LegacyFlickerTest.imeLayerBecomesInvisible() {
    assertLayers {
        this.isVisible(ComponentNameMatcher.IME).then().isInvisible(ComponentNameMatcher.IME)
    }
}

fun LegacyFlickerTest.imeWindowIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertWm {
            this.isNonAppWindowVisible(ComponentNameMatcher.IME)
                .then()
                .isNonAppWindowInvisible(ComponentNameMatcher.IME)
                .then()
                .isNonAppWindowVisible(ComponentNameMatcher.IME)
        }
    } else {
        assertWm { this.isNonAppWindowVisible(ComponentNameMatcher.IME) }
    }
}

fun LegacyFlickerTest.imeWindowBecomesVisible() {
    assertWm {
        this.isNonAppWindowInvisible(ComponentNameMatcher.IME)
            .then()
            .isNonAppWindowVisible(ComponentNameMatcher.IME)
    }
}

fun LegacyFlickerTest.imeWindowBecomesInvisible() {
    assertWm {
        this.isNonAppWindowVisible(ComponentNameMatcher.IME)
            .then()
            .isNonAppWindowInvisible(ComponentNameMatcher.IME)
    }
}

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

package com.android.server.wm.flicker.ime

import android.platform.helpers.IAppHelper
import com.android.server.wm.flicker.FlickerTestParameter

const val IME_WINDOW_TITLE = "InputMethod"

fun FlickerTestParameter.imeLayerIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertLayers {
            this.isVisible(IME_WINDOW_TITLE)
                .then()
                .isInvisible(IME_WINDOW_TITLE)
                .then()
                .isVisible(IME_WINDOW_TITLE)
        }
    } else {
        assertLayers {
            this.isVisible(IME_WINDOW_TITLE)
        }
    }
}

fun FlickerTestParameter.imeLayerBecomesVisible() {
    assertLayers {
        this.isInvisible(IME_WINDOW_TITLE)
            .then()
            .isVisible(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeLayerBecomesInvisible() {
    assertLayers {
        this.isVisible(IME_WINDOW_TITLE)
            .then()
            .isInvisible(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeAppLayerIsAlwaysVisible(testApp: IAppHelper) {
    assertLayers {
        this.isVisible(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeAppWindowIsAlwaysVisible(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeWindowIsAlwaysVisible(rotatesScreen: Boolean = false) {
    if (rotatesScreen) {
        assertWm {
            this.showsNonAppWindow(IME_WINDOW_TITLE)
                .then()
                .hidesNonAppWindow(IME_WINDOW_TITLE)
                .then()
                .showsNonAppWindow(IME_WINDOW_TITLE)
        }
    } else {
        assertWm {
            this.showsNonAppWindow(IME_WINDOW_TITLE)
        }
    }
}

fun FlickerTestParameter.imeWindowBecomesVisible() {
    assertWm {
        this.hidesNonAppWindow(IME_WINDOW_TITLE)
            .then()
            .showsNonAppWindow(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeWindowBecomesInvisible() {
    assertWm {
        this.showsNonAppWindow(IME_WINDOW_TITLE)
            .then()
            .hidesNonAppWindow(IME_WINDOW_TITLE)
    }
}

fun FlickerTestParameter.imeAppWindowIsAlwaysVisible(
    testApp: IAppHelper,
    rotatesScreen: Boolean = false
) {
    if (rotatesScreen) {
        assertWm {
            this.showsAppWindow(testApp.getPackage())
                .then()
                .hidesAppWindow(testApp.getPackage())
                .then()
                .showsAppWindow(testApp.getPackage())
        }
    } else {
        assertWm {
            this.showsAppWindow(testApp.getPackage())
        }
    }
}

fun FlickerTestParameter.imeAppWindowBecomesVisible(windowName: String) {
    assertWm {
        this.hidesAppWindow(windowName)
            .then()
            .showsAppWindow(windowName)
    }
}

fun FlickerTestParameter.imeAppWindowBecomesInvisible(testApp: IAppHelper) {
    assertWm {
        this.showsAppWindowOnTop(testApp.getPackage())
            .then()
            .appWindowNotOnTop(testApp.getPackage())
    }
}

fun FlickerTestParameter.imeAppLayerBecomesInvisible(testApp: IAppHelper) {
    assertLayers {
        this.isVisible(testApp.getPackage())
            .then()
            .isInvisible(testApp.getPackage())
    }
}
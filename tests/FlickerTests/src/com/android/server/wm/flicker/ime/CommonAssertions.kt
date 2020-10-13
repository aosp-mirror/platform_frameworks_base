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
import com.android.server.wm.flicker.dsl.LayersAssertion
import com.android.server.wm.flicker.dsl.WmAssertion

const val IME_WINDOW_TITLE = "InputMethod"

@JvmOverloads
fun LayersAssertion.imeLayerBecomesVisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeLayerBecomesVisible", enabled, bugId) {
        this.hidesLayer(IME_WINDOW_TITLE)
                .then()
                .showsLayer(IME_WINDOW_TITLE)
    }
}

fun LayersAssertion.imeLayerBecomesInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeLayerBecomesInvisible", enabled, bugId) {
        this.showsLayer(IME_WINDOW_TITLE)
                .then()
                .hidesLayer(IME_WINDOW_TITLE)
    }
}

fun LayersAssertion.imeAppLayerIsAlwaysVisible(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeAppLayerIsAlwaysVisible", enabled, bugId) {
        this.showsLayer(testApp.getPackage())
    }
}

fun WmAssertion.imeAppWindowIsAlwaysVisible(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeAppWindowIsAlwaysVisible", enabled, bugId) {
        this.showsAppWindowOnTop(testApp.getPackage())
    }
}

fun WmAssertion.imeWindowBecomesInvisible(
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeWindowBecomesInvisible", enabled, bugId) {
        this.showsNonAppWindow(IME_WINDOW_TITLE)
                .then()
                .hidesNonAppWindow(IME_WINDOW_TITLE)
    }
}

fun WmAssertion.imeAppWindowBecomesInvisible(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeAppWindowBecomesInvisible", enabled, bugId) {
        this.showsAppWindowOnTop(testApp.getPackage())
                .then()
                .appWindowNotOnTop(testApp.getPackage())
    }
}

fun LayersAssertion.imeAppLayerBecomesInvisible(
    testApp: IAppHelper,
    bugId: Int = 0,
    enabled: Boolean = bugId == 0
) {
    all("imeAppLayerBecomesInvisible", enabled, bugId) {
        this.skipUntilFirstAssertion()
                .showsLayer(testApp.getPackage())
                .then()
                .hidesLayer(testApp.getPackage())
    }
}
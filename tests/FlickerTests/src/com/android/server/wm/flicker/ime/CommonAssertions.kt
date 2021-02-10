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
import com.android.server.wm.flicker.dsl.LayersAssertionBuilder
import com.android.server.wm.flicker.dsl.WmAssertionBuilder

const val IME_WINDOW_TITLE = "InputMethod"

@JvmOverloads
fun LayersAssertionBuilder.imeLayerBecomesVisible(bugId: Int = 0) {
    all("imeLayerBecomesVisible", bugId) {
        this.hidesLayer(IME_WINDOW_TITLE)
                .then()
                .showsLayer(IME_WINDOW_TITLE)
    }
}

@JvmOverloads
fun LayersAssertionBuilder.imeLayerBecomesInvisible(bugId: Int = 0) {
    all("imeLayerBecomesInvisible", bugId) {
        this.showsLayer(IME_WINDOW_TITLE)
                .then()
                .hidesLayer(IME_WINDOW_TITLE)
    }
}

@JvmOverloads
fun LayersAssertionBuilder.imeAppLayerIsAlwaysVisible(testApp: IAppHelper, bugId: Int = 0) {
    all("imeAppLayerIsAlwaysVisible", bugId) {
        this.showsLayer(testApp.getPackage())
    }
}

@JvmOverloads
fun WmAssertionBuilder.imeAppWindowIsAlwaysVisible(testApp: IAppHelper, bugId: Int = 0) {
    all("imeAppWindowIsAlwaysVisible", bugId) {
        this.showsAppWindowOnTop(testApp.getPackage())
    }
}

@JvmOverloads
fun WmAssertionBuilder.imeWindowBecomesVisible(bugId: Int = 0) {
    all("imeWindowBecomesVisible", bugId) {
        this.hidesNonAppWindow(IME_WINDOW_TITLE)
                .then()
                .showsNonAppWindow(IME_WINDOW_TITLE)
    }
}

@JvmOverloads
fun WmAssertionBuilder.imeWindowBecomesInvisible(bugId: Int = 0) {
    all("imeWindowBecomesInvisible", bugId) {
        this.showsNonAppWindow(IME_WINDOW_TITLE)
                .then()
                .hidesNonAppWindow(IME_WINDOW_TITLE)
    }
}

@JvmOverloads
fun WmAssertionBuilder.imeAppWindowBecomesVisible(windowName: String, bugId: Int = 0) {
    all("imeAppWindowBecomesVisible", bugId) {
        this.hidesAppWindow(windowName)
                .then()
                .showsAppWindow(windowName)
    }
}

@JvmOverloads
fun WmAssertionBuilder.imeAppWindowBecomesInvisible(testApp: IAppHelper, bugId: Int = 0) {
    all("imeAppWindowBecomesInvisible", bugId) {
        this.showsAppWindowOnTop(testApp.getPackage())
                .then()
                .appWindowNotOnTop(testApp.getPackage())
    }
}

@JvmOverloads
fun LayersAssertionBuilder.imeAppLayerBecomesInvisible(testApp: IAppHelper, bugId: Int = 0) {
    all("imeAppLayerBecomesInvisible", bugId) {
        this.skipUntilFirstAssertion()
                .showsLayer(testApp.getPackage())
                .then()
                .hidesLayer(testApp.getPackage())
    }
}
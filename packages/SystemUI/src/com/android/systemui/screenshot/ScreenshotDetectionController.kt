/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.content.pm.PackageManager
import android.view.IWindowManager
import android.view.ViewGroup
import android.widget.TextView
import com.android.systemui.R
import javax.inject.Inject

class ScreenshotDetectionController
@Inject
constructor(
    private val windowManager: IWindowManager,
    private val packageManager: PackageManager,
) {
    /**
     * Notify potentially listening apps of the screenshot. Return a list of the names of the apps
     * notified.
     */
    fun maybeNotifyOfScreenshot(data: ScreenshotData): List<CharSequence> {
        // TODO: actually ask the window manager once API is available.
        return listOf()
    }

    fun populateView(view: ViewGroup, appNames: List<CharSequence>) {
        assert(appNames.isNotEmpty())

        val textView: TextView = view.requireViewById(R.id.screenshot_detection_notice_text)
        if (appNames.size == 1) {
            textView.text =
                view.resources.getString(R.string.screenshot_detected_template, appNames[0])
        } else {
            textView.text =
                view.resources.getString(
                    R.string.screenshot_detected_multiple_template,
                    appNames[0]
                )
        }
    }
}

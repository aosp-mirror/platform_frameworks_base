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

package com.android.systemui

import android.view.Surface
import android.view.Surface.Rotation
import com.android.systemui.statusbar.commandline.ParseableCommand
import com.android.systemui.statusbar.commandline.Type
import java.io.PrintWriter

class StatusBarInsetsCommand(
    private val callback: Callback,
) : ParseableCommand(NAME) {

    val bottomMargin: BottomMarginCommand? by subCommand(BottomMarginCommand())

    override fun execute(pw: PrintWriter) {
        callback.onExecute(command = this, pw)
    }

    interface Callback {
        fun onExecute(command: StatusBarInsetsCommand, printWriter: PrintWriter)
    }

    companion object {
        const val NAME = "status-bar-insets"
    }
}

class BottomMarginCommand : ParseableCommand(NAME) {

    private val rotationDegrees: Int? by
        param(
            longName = "rotation",
            shortName = "r",
            description = "For which rotation the margin should be set. One of 0, 90, 180, 270",
            valueParser = Type.Int,
        )

    @Rotation
    val rotationValue: Int?
        get() = ROTATION_DEGREES_TO_VALUE_MAPPING[rotationDegrees]

    val marginBottomDp: Float? by
        param(
            longName = "margin",
            shortName = "m",
            description = "Margin amount, in dp. Can be a fractional value, such as 10.5",
            valueParser = Type.Float,
        )

    override fun execute(pw: PrintWriter) {
        // Not needed for a subcommand
    }

    companion object {
        const val NAME = "bottom-margin"
        private val ROTATION_DEGREES_TO_VALUE_MAPPING =
            mapOf(
                0 to Surface.ROTATION_0,
                90 to Surface.ROTATION_90,
                180 to Surface.ROTATION_180,
                270 to Surface.ROTATION_270,
            )

        val ROTATION_DEGREES_OPTIONS: Set<Int> = ROTATION_DEGREES_TO_VALUE_MAPPING.keys
    }
}

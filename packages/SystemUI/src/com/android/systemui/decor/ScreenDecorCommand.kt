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

package com.android.systemui.decor

import android.graphics.Color
import android.graphics.Path
import android.util.PathParser
import com.android.systemui.statusbar.commandline.ParseableCommand
import com.android.systemui.statusbar.commandline.Type
import com.android.systemui.statusbar.commandline.map
import java.io.PrintWriter

/** Debug screen-decor command to be handled by the SystemUI command line interface */
class ScreenDecorCommand(
    private val callback: Callback,
) : ParseableCommand(SCREEN_DECOR_CMD_NAME) {
    val debug: Boolean? by
        param(
            longName = "debug",
            description =
                "Enter or exits debug mode. Effectively makes the corners visible and allows " +
                    "for overriding the path data for the anti-aliasing corner paths and display " +
                    "cutout.",
            valueParser = Type.Boolean,
        )

    val color: Int? by
        param(
            longName = "color",
            shortName = "c",
            description =
                "Set a specific color for the debug assets. See Color#parseString() for " +
                    "accepted inputs.",
            valueParser = Type.String.map { it.toColorIntOrNull() }
        )

    val roundedTop: RoundedCornerSubCommand? by subCommand(RoundedCornerSubCommand("rounded-top"))

    val roundedBottom: RoundedCornerSubCommand? by
        subCommand(RoundedCornerSubCommand("rounded-bottom"))

    override fun execute(pw: PrintWriter) {
        callback.onExecute(this, pw)
    }

    override fun toString(): String {
        return "ScreenDecorCommand(" +
            "debug=$debug, " +
            "color=$color, " +
            "roundedTop=$roundedTop, " +
            "roundedBottom=$roundedBottom)"
    }

    /** For use in ScreenDecorations.java, define a Callback */
    interface Callback {
        fun onExecute(cmd: ScreenDecorCommand, pw: PrintWriter)
    }

    companion object {
        const val SCREEN_DECOR_CMD_NAME = "screen-decor"
    }
}

/**
 * Defines a subcommand suitable for `rounded-top` and `rounded-bottom`. They both have the same
 * API.
 */
class RoundedCornerSubCommand(name: String) : ParseableCommand(name) {
    val height by
        param(
                longName = "height",
                description = "The height of a corner, in pixels.",
                valueParser = Type.Int,
            )
            .required()

    val width by
        param(
                longName = "width",
                description =
                    "The width of the corner, in pixels. Likely should be equal to the height.",
                valueParser = Type.Int,
            )
            .required()

    val pathData by
        param(
                longName = "path-data",
                shortName = "d",
                description =
                    "PathParser-compatible path string to be rendered as the corner drawable. " +
                        "This path should be a closed arc oriented as the top-left corner " +
                        "of the device",
                valueParser = Type.String.map { it.toPathOrNull() }
            )
            .required()

    val viewportHeight: Float? by
        param(
            longName = "viewport-height",
            description =
                "The height of the viewport for the given path string. " +
                    "If null, the corner height will be used.",
            valueParser = Type.Float,
        )

    val scaleY: Float
        get() = viewportHeight?.let { height.toFloat() / it } ?: 1.0f

    val viewportWidth: Float? by
        param(
            longName = "viewport-width",
            description =
                "The width of the viewport for the given path string. " +
                    "If null, the corner width will be used.",
            valueParser = Type.Float,
        )

    val scaleX: Float
        get() = viewportWidth?.let { width.toFloat() / it } ?: 1.0f

    override fun execute(pw: PrintWriter) {
        // Not needed for a subcommand
    }

    override fun toString(): String {
        return "RoundedCornerSubCommand(" +
            "height=$height," +
            " width=$width," +
            " pathData='$pathData'," +
            " viewportHeight=$viewportHeight," +
            " viewportWidth=$viewportWidth)"
    }

    fun toRoundedCornerDebugModel(): DebugRoundedCornerModel =
        DebugRoundedCornerModel(
            path = pathData,
            width = width,
            height = height,
            scaleX = scaleX,
            scaleY = scaleY,
        )
}

fun String.toPathOrNull(): Path? =
    try {
        PathParser.createPathFromPathData(this)
    } catch (e: Exception) {
        null
    }

fun String.toColorIntOrNull(): Int? =
    try {
        Color.parseColor(this)
    } catch (e: Exception) {
        null
    }

/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.annotation.ColorInt
import android.content.Context
import android.graphics.Rect
import android.view.InsetsFlags
import android.view.ViewDebug
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS
import android.view.WindowInsetsController.Appearance
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.util.ContrastColorUtil
import com.android.internal.view.AppearanceRegion
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import javax.inject.Inject

data class LetterboxAppearance(
    @Appearance val appearance: Int,
    val appearanceRegions: List<AppearanceRegion>,
) {
    override fun toString(): String {
        val appearanceString =
                ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", appearance)
        return "LetterboxAppearance{$appearanceString, $appearanceRegions}"
    }
}

/**
 * Responsible for calculating the [Appearance] and [AppearanceRegion] for the status bar when apps
 * are letterboxed.
 */
@SysUISingleton
class LetterboxAppearanceCalculator
@Inject
constructor(
    context: Context,
    dumpManager: DumpManager,
    private val letterboxBackgroundProvider: LetterboxBackgroundProvider,
) : Dumpable {

    private val darkAppearanceIconColor = context.getColor(
        // For a dark background status bar, use a *light* icon color.
        com.android.settingslib.R.color.light_mode_icon_color_single_tone
    )
    private val lightAppearanceIconColor = context.getColor(
        // For a light background status bar, use a *dark* icon color.
        com.android.settingslib.R.color.dark_mode_icon_color_single_tone
    )

    init {
        dumpManager.registerCriticalDumpable(this)
    }

    private var lastAppearance: Int? = null
    private var lastAppearanceRegions: List<AppearanceRegion>? = null
    private var lastLetterboxes: List<LetterboxDetails>? = null
    private var lastLetterboxAppearance: LetterboxAppearance? = null

    fun getLetterboxAppearance(
        @Appearance originalAppearance: Int,
        originalAppearanceRegions: List<AppearanceRegion>,
        letterboxes: List<LetterboxDetails>,
        statusBarBounds: BoundsPair,
    ): LetterboxAppearance {
        lastAppearance = originalAppearance
        lastAppearanceRegions = originalAppearanceRegions
        lastLetterboxes = letterboxes
        return getLetterboxAppearanceInternal(
                letterboxes, originalAppearance, originalAppearanceRegions, statusBarBounds)
            .also { lastLetterboxAppearance = it }
    }

    private fun getLetterboxAppearanceInternal(
        letterboxes: List<LetterboxDetails>,
        originalAppearance: Int,
        originalAppearanceRegions: List<AppearanceRegion>,
        statusBarBounds: BoundsPair,
    ): LetterboxAppearance {
        if (isScrimNeeded(letterboxes, statusBarBounds)) {
            return originalAppearanceWithScrim(originalAppearance, originalAppearanceRegions)
        }
        val appearance = appearanceWithoutScrim(originalAppearance)
        val appearanceRegions = getAppearanceRegions(originalAppearanceRegions, letterboxes)
        return LetterboxAppearance(appearance, appearanceRegions)
    }

    private fun isScrimNeeded(
        letterboxes: List<LetterboxDetails>,
        statusBarBounds: BoundsPair,
    ): Boolean {
        if (isOuterLetterboxMultiColored()) {
            return true
        }
        return letterboxes.any { letterbox ->
            letterbox.letterboxInnerBounds.overlapsWith(statusBarBounds.start) ||
                letterbox.letterboxInnerBounds.overlapsWith(statusBarBounds.end)
        }
    }

    private fun getAppearanceRegions(
        originalAppearanceRegions: List<AppearanceRegion>,
        letterboxes: List<LetterboxDetails>
    ): List<AppearanceRegion> {
        return sanitizeAppearanceRegions(originalAppearanceRegions, letterboxes) +
            getAllOuterAppearanceRegions(letterboxes)
    }

    private fun sanitizeAppearanceRegions(
        originalAppearanceRegions: List<AppearanceRegion>,
        letterboxes: List<LetterboxDetails>
    ): List<AppearanceRegion> =
        originalAppearanceRegions.map { appearanceRegion ->
            val matchingLetterbox =
                letterboxes.find { it.letterboxFullBounds == appearanceRegion.bounds }
            if (matchingLetterbox == null) {
                appearanceRegion
            } else {
                // When WindowManager sends appearance regions for an app, it sends them for the
                // full bounds of its window.
                // Here we want the bounds to be only for the inner bounds of the letterboxed app.
                AppearanceRegion(
                    appearanceRegion.appearance, matchingLetterbox.letterboxInnerBounds)
            }
        }

    private fun originalAppearanceWithScrim(
        @Appearance originalAppearance: Int,
        originalAppearanceRegions: List<AppearanceRegion>
    ): LetterboxAppearance {
        return LetterboxAppearance(
            originalAppearance or APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS,
            originalAppearanceRegions)
    }

    @Appearance
    private fun appearanceWithoutScrim(@Appearance originalAppearance: Int): Int =
        originalAppearance and APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS.inv()

    private fun getAllOuterAppearanceRegions(
        letterboxes: List<LetterboxDetails>
    ): List<AppearanceRegion> = letterboxes.map(this::getOuterAppearanceRegions).flatten()

    private fun getOuterAppearanceRegions(
        letterboxDetails: LetterboxDetails
    ): List<AppearanceRegion> {
        @Appearance val outerAppearance = getOuterAppearance()
        return getVisibleOuterBounds(letterboxDetails).map { bounds ->
            AppearanceRegion(outerAppearance, bounds)
        }
    }

    private fun getVisibleOuterBounds(letterboxDetails: LetterboxDetails): List<Rect> {
        val inner = letterboxDetails.letterboxInnerBounds
        val outer = letterboxDetails.letterboxFullBounds
        val top = Rect(outer.left, outer.top, outer.right, inner.top)
        val left = Rect(outer.left, outer.top, inner.left, outer.bottom)
        val right = Rect(inner.right, outer.top, outer.right, outer.bottom)
        val bottom = Rect(outer.left, inner.bottom, outer.right, outer.bottom)
        return listOf(left, top, right, bottom).filter { !it.isEmpty }
    }

    @Appearance
    private fun getOuterAppearance(): Int {
        val backgroundColor = outerLetterboxBackgroundColor()
        val darkAppearanceContrast =
            ContrastColorUtil.calculateContrast(darkAppearanceIconColor, backgroundColor)
        val lightAppearanceContrast =
            ContrastColorUtil.calculateContrast(lightAppearanceIconColor, backgroundColor)
        return if (lightAppearanceContrast > darkAppearanceContrast) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else {
            0 // APPEARANCE_DEFAULT
        }
    }

    @ColorInt
    private fun outerLetterboxBackgroundColor(): Int {
        return letterboxBackgroundProvider.letterboxBackgroundColor
    }

    private fun isOuterLetterboxMultiColored(): Boolean {
        return letterboxBackgroundProvider.isLetterboxBackgroundMultiColored
    }

    private fun Rect.overlapsWith(other: Rect): Boolean {
        if (this.contains(other) || other.contains(this)) {
            return false
        }
        return this.intersects(other.left, other.top, other.right, other.bottom)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(
            """
           lastAppearance: ${lastAppearance?.toAppearanceString()}
           lastAppearanceRegion: $lastAppearanceRegions,
           lastLetterboxes: $lastLetterboxes,
           lastLetterboxAppearance: $lastLetterboxAppearance
       """.trimIndent())
    }
}

private fun Int.toAppearanceString(): String =
    ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", this)

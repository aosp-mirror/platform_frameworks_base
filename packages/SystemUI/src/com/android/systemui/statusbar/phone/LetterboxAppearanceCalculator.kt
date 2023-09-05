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
import com.android.systemui.statusbar.core.StatusBarInitializer.OnStatusBarViewInitializedListener
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentComponent
import java.io.PrintWriter
import java.util.Arrays
import javax.inject.Inject

class LetterboxAppearance(
    @Appearance val appearance: Int,
    val appearanceRegions: Array<AppearanceRegion>
) {
    override fun toString(): String {
        val appearanceString =
                ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", appearance)
        return "LetterboxAppearance{$appearanceString, ${appearanceRegions.contentToString()}}"
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
    private val lightBarController: LightBarController,
    dumpManager: DumpManager,
    private val letterboxBackgroundProvider: LetterboxBackgroundProvider,
) : OnStatusBarViewInitializedListener, Dumpable {

    init {
        dumpManager.registerCriticalDumpable(this)
    }

    private var statusBarBoundsProvider: StatusBarBoundsProvider? = null

    private var lastAppearance: Int? = null
    private var lastAppearanceRegions: Array<AppearanceRegion>? = null
    private var lastLetterboxes: Array<LetterboxDetails>? = null
    private var lastLetterboxAppearance: LetterboxAppearance? = null

    fun getLetterboxAppearance(
        @Appearance originalAppearance: Int,
        originalAppearanceRegions: Array<AppearanceRegion>,
        letterboxes: Array<LetterboxDetails>
    ): LetterboxAppearance {
        lastAppearance = originalAppearance
        lastAppearanceRegions = originalAppearanceRegions
        lastLetterboxes = letterboxes
        return getLetterboxAppearanceInternal(
                letterboxes, originalAppearance, originalAppearanceRegions)
            .also { lastLetterboxAppearance = it }
    }

    private fun getLetterboxAppearanceInternal(
        letterboxes: Array<LetterboxDetails>,
        originalAppearance: Int,
        originalAppearanceRegions: Array<AppearanceRegion>
    ): LetterboxAppearance {
        if (isScrimNeeded(letterboxes)) {
            return originalAppearanceWithScrim(originalAppearance, originalAppearanceRegions)
        }
        val appearance = appearanceWithoutScrim(originalAppearance)
        val appearanceRegions = getAppearanceRegions(originalAppearanceRegions, letterboxes)
        return LetterboxAppearance(appearance, appearanceRegions.toTypedArray())
    }

    private fun isScrimNeeded(letterboxes: Array<LetterboxDetails>): Boolean {
        if (isOuterLetterboxMultiColored()) {
            return true
        }
        return letterboxes.any { letterbox ->
            letterbox.letterboxInnerBounds.overlapsWith(getStartSideIconBounds()) ||
                letterbox.letterboxInnerBounds.overlapsWith(getEndSideIconsBounds())
        }
    }

    private fun getAppearanceRegions(
        originalAppearanceRegions: Array<AppearanceRegion>,
        letterboxes: Array<LetterboxDetails>
    ): List<AppearanceRegion> {
        return sanitizeAppearanceRegions(originalAppearanceRegions, letterboxes) +
            getAllOuterAppearanceRegions(letterboxes)
    }

    private fun sanitizeAppearanceRegions(
        originalAppearanceRegions: Array<AppearanceRegion>,
        letterboxes: Array<LetterboxDetails>
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
        originalAppearanceRegions: Array<AppearanceRegion>
    ): LetterboxAppearance {
        return LetterboxAppearance(
            originalAppearance or APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS,
            originalAppearanceRegions)
    }

    @Appearance
    private fun appearanceWithoutScrim(@Appearance originalAppearance: Int): Int =
        originalAppearance and APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS.inv()

    private fun getAllOuterAppearanceRegions(
        letterboxes: Array<LetterboxDetails>
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
            ContrastColorUtil.calculateContrast(
                lightBarController.darkAppearanceIconColor, backgroundColor)
        val lightAppearanceContrast =
            ContrastColorUtil.calculateContrast(
                lightBarController.lightAppearanceIconColor, backgroundColor)
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

    private fun getEndSideIconsBounds(): Rect {
        return statusBarBoundsProvider?.visibleEndSideBounds ?: Rect()
    }

    private fun getStartSideIconBounds(): Rect {
        return statusBarBoundsProvider?.visibleStartSideBounds ?: Rect()
    }

    override fun onStatusBarViewInitialized(component: StatusBarFragmentComponent) {
        statusBarBoundsProvider = component.boundsProvider
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
           lastAppearanceRegion: ${Arrays.toString(lastAppearanceRegions)},
           lastLetterboxes: ${Arrays.toString(lastLetterboxes)},
           lastLetterboxAppearance: $lastLetterboxAppearance
       """.trimIndent())
    }
}

private fun Int.toAppearanceString(): String =
    ViewDebug.flagsToString(InsetsFlags::class.java, "appearance", this)

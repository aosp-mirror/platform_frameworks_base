/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.monet

import android.annotation.ColorInt
import android.app.WallpaperColors
import android.graphics.Color
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.internal.graphics.cam.CamUtils.lstarFromInt
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

const val TAG = "ColorScheme"

const val ACCENT1_CHROMA = 48.0f
const val ACCENT2_CHROMA = 16.0f
const val ACCENT3_CHROMA = 32.0f
const val ACCENT3_HUE_SHIFT = 60.0f

const val NEUTRAL1_CHROMA = 4.0f
const val NEUTRAL2_CHROMA = 8.0f

const val GOOGLE_BLUE = 0xFF1b6ef3.toInt()

const val MIN_CHROMA = 15
const val MIN_LSTAR = 10

public class ColorScheme(@ColorInt seed: Int, val darkTheme: Boolean) {

    val accent1: List<Int>
    val accent2: List<Int>
    val accent3: List<Int>
    val neutral1: List<Int>
    val neutral2: List<Int>

    constructor(wallpaperColors: WallpaperColors, darkTheme: Boolean):
            this(getSeedColor(wallpaperColors), darkTheme)

    val allAccentColors: List<Int>
        get() {
            val allColors = mutableListOf<Int>()
            allColors.addAll(accent1)
            allColors.addAll(accent2)
            allColors.addAll(accent3)
            return allColors
        }

    val allNeutralColors: List<Int>
        get() {
            val allColors = mutableListOf<Int>()
            allColors.addAll(neutral1)
            allColors.addAll(neutral2)
            return allColors
        }

    val backgroundColor
        get() = ColorUtils.setAlphaComponent(if (darkTheme) neutral1[8] else neutral1[0], 0xFF)

    val accentColor
        get() = ColorUtils.setAlphaComponent(if (darkTheme) accent1[2] else accent1[6], 0xFF)

    init {
        val seedArgb = if (seed == Color.TRANSPARENT) GOOGLE_BLUE else seed
        val camSeed = Cam.fromInt(seedArgb)
        val hue = camSeed.hue
        val chroma = camSeed.chroma.coerceAtLeast(ACCENT1_CHROMA)
        accent1 = Shades.of(hue, chroma).toList()
        accent2 = Shades.of(hue, ACCENT2_CHROMA).toList()
        accent3 = Shades.of(hue + ACCENT3_HUE_SHIFT, ACCENT3_CHROMA).toList()
        neutral1 = Shades.of(hue, NEUTRAL1_CHROMA).toList()
        neutral2 = Shades.of(hue, NEUTRAL2_CHROMA).toList()
    }

    override fun toString(): String {
        return "ColorScheme {\n" +
                "  neutral1: ${humanReadable(neutral1)}\n" +
                "  neutral2: ${humanReadable(neutral2)}\n" +
                "  accent1: ${humanReadable(accent1)}\n" +
                "  accent2: ${humanReadable(accent2)}\n" +
                "  accent3: ${humanReadable(accent3)}\n" +
                "}"
    }

    companion object {
        /**
         * Identifies a color to create a color scheme from.
         *
         * @param wallpaperColors Colors extracted from an image via quantization.
         * @return ARGB int representing the color
         */
        @JvmStatic
        @ColorInt
        fun getSeedColor(wallpaperColors: WallpaperColors): Int {
            return getSeedColors(wallpaperColors).first()
        }

        /**
         * Filters and ranks colors from WallpaperColors.
         *
         * @param wallpaperColors Colors extracted from an image via quantization.
         * @return List of ARGB ints, ordered from highest scoring to lowest.
         */
        @JvmStatic
        fun getSeedColors(wallpaperColors: WallpaperColors): List<Int> {
            val totalPopulation = wallpaperColors.allColors.values.reduce { a, b -> a + b }
                    .toDouble()
            val totalPopulationMeaningless = (totalPopulation == 0.0)
            if (totalPopulationMeaningless) {
                // WallpaperColors with a population of 0 indicate the colors didn't come from
                // quantization. Instead of scoring, trust the ordering of the provided primary
                // secondary/tertiary colors.
                //
                // In this case, the colors are usually from a Live Wallpaper.
                val distinctColors = wallpaperColors.mainColors.map {
                    it.toArgb()
                }.distinct().filter {
                    val cam = Cam.fromInt(it)
                    val lstar = lstarFromInt(it)
                    cam.chroma >= MIN_CHROMA && lstar >= MIN_LSTAR
                }.toList()

                if (distinctColors.isEmpty()) {
                    return listOf(GOOGLE_BLUE)
                }
                return distinctColors
            }

            val intToProportion = wallpaperColors.allColors.mapValues {
                it.value.toDouble() / totalPopulation
            }
            val intToCam = wallpaperColors.allColors.mapValues { Cam.fromInt(it.key) }

            // Get an array with 360 slots. A slot contains the percentage of colors with that hue.
            val hueProportions = huePopulations(intToCam, intToProportion)
            // Map each color to the percentage of the image with its hue.
            val intToHueProportion = wallpaperColors.allColors.mapValues {
                val cam = intToCam[it.key]!!
                val hue = cam.hue.roundToInt()
                var proportion = 0.0
                for (i in hue - 15..hue + 15) {
                    proportion += hueProportions[wrapDegrees(i)]
                }
                proportion
            }
            // Remove any inappropriate seed colors. For example, low chroma colors look grayscale
            // raising their chroma will turn them to a much louder color that may not have been
            // in the image.
            val filteredIntToCam = intToCam.filter {
                val cam = it.value
                val lstar = lstarFromInt(it.key)
                val proportion = intToHueProportion[it.key]!!
                cam.chroma >= MIN_CHROMA && lstar >= MIN_LSTAR &&
                        (totalPopulationMeaningless || proportion > 0.01)
            }
            // Sort the colors by score, from high to low.
            val seeds = mutableListOf<Int>()
            val intToScoreIntermediate = filteredIntToCam.mapValues {
                score(it.value, intToHueProportion[it.key]!!)
            }
            val intToScore = intToScoreIntermediate.entries.toMutableList()
            intToScore.sortByDescending { it.value }

            // Go through the colors, from high score to low score. If there isn't already a seed
            // color with a hue close to color being examined, add the color being examined to the
            // seed colors.
            for (entry in intToScore) {
                val int = entry.key
                val existingSeedNearby = seeds.find {
                    val hueA = intToCam[int]!!.hue
                    val hueB = intToCam[it]!!.hue
                    hueDiff(hueA, hueB) < 15 } != null
                if (existingSeedNearby) {
                    continue
                }
                seeds.add(int)
            }

            if (seeds.isEmpty()) {
                // Use gBlue 500 if there are 0 colors
                seeds.add(GOOGLE_BLUE)
            }

            return seeds
        }

        private fun wrapDegrees(degrees: Int): Int {
            return when {
                degrees < 0 -> {
                    (degrees % 360) + 360
                }
                degrees >= 360 -> {
                    degrees % 360
                }
                else -> {
                    degrees
                }
            }
        }

        private fun hueDiff(a: Float, b: Float): Float {
            return 180f - ((a - b).absoluteValue - 180f).absoluteValue
        }

        private fun humanReadable(colors: List<Int>): String {
            return colors.joinToString { "#" + Integer.toHexString(it) }
        }

        private fun score(cam: Cam, proportion: Double): Double {
            val proportionScore = 0.7 * 100.0 * proportion
            val chromaScore = if (cam.chroma < ACCENT1_CHROMA) 0.1 * (cam.chroma - ACCENT1_CHROMA)
            else 0.3 * (cam.chroma - ACCENT1_CHROMA)
            return chromaScore + proportionScore
        }

        private fun huePopulations(
            camByColor: Map<Int, Cam>,
            populationByColor: Map<Int, Double>
        ): List<Double> {
            val huePopulation = List(size = 360, init = { 0.0 }).toMutableList()

            for (entry in populationByColor.entries) {
                val population = populationByColor[entry.key]!!
                val cam = camByColor[entry.key]!!
                val hue = cam.hue.roundToInt() % 360
                huePopulation[hue] = huePopulation[hue] + population
            }

            return huePopulation
        }
    }
}
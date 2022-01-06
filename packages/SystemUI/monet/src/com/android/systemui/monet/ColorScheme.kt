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
const val GOOGLE_BLUE = 0xFF1b6ef3.toInt()
const val MIN_CHROMA = 5

enum class ChromaStrategy {
    EQ, GTE
}

enum class HueStrategy {
    SOURCE, ADD, SUBTRACT
}

class Chroma(val strategy: ChromaStrategy, val value: Double) {
    fun get(sourceChroma: Double): Double {
        return when (strategy) {
            ChromaStrategy.EQ -> value
            ChromaStrategy.GTE -> sourceChroma.coerceAtLeast(value)
        }
    }
}

class Hue(val strategy: HueStrategy = HueStrategy.SOURCE, val value: Double = 0.0) {
    fun get(sourceHue: Double): Double {
        return when (strategy) {
            HueStrategy.SOURCE -> sourceHue
            HueStrategy.ADD -> ColorScheme.wrapDegreesDouble(sourceHue + value)
            HueStrategy.SUBTRACT -> ColorScheme.wrapDegreesDouble(sourceHue - value)
        }
    }
}

class TonalSpec(val hue: Hue = Hue(), val chroma: Chroma) {
    fun shades(sourceColor: Cam): List<Int> {
        val hue = hue.get(sourceColor.hue.toDouble())
        val chroma = chroma.get(sourceColor.chroma.toDouble())
        return Shades.of(hue.toFloat(), chroma.toFloat()).toList()
    }
}

class CoreSpec(
    val a1: TonalSpec,
    val a2: TonalSpec,
    val a3: TonalSpec,
    val n1: TonalSpec,
    val n2: TonalSpec
)

enum class Style(val coreSpec: CoreSpec) {
    SPRITZ(CoreSpec(
            a1 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0)),
            a2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0)),
            a3 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0)),
            n1 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0)),
            n2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0))
    )),
    TONAL_SPOT(CoreSpec(
            a1 = TonalSpec(chroma = Chroma(ChromaStrategy.GTE, 48.0)),
            a2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 16.0)),
            a3 = TonalSpec(Hue(HueStrategy.ADD, 60.0), Chroma(ChromaStrategy.EQ, 24.0)),
            n1 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 4.0)),
            n2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 8.0))
    )),
    VIBRANT(CoreSpec(
            a1 = TonalSpec(chroma = Chroma(ChromaStrategy.GTE, 48.0)),
            a2 = TonalSpec(Hue(HueStrategy.ADD, 10.0), Chroma(ChromaStrategy.EQ, 24.0)),
            a3 = TonalSpec(Hue(HueStrategy.ADD, 20.0), Chroma(ChromaStrategy.GTE, 32.0)),
            n1 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 8.0)),
            n2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 16.0))
    )),
    EXPRESSIVE(CoreSpec(
            a1 = TonalSpec(Hue(HueStrategy.SUBTRACT, 40.0), Chroma(ChromaStrategy.GTE, 64.0)),
            a2 = TonalSpec(Hue(HueStrategy.ADD, 20.0), Chroma(ChromaStrategy.EQ, 24.0)),
            a3 = TonalSpec(Hue(HueStrategy.SUBTRACT, 80.0), Chroma(ChromaStrategy.GTE, 64.0)),
            n1 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 16.0)),
            n2 = TonalSpec(chroma = Chroma(ChromaStrategy.EQ, 32.0))
    )),
}

public class ColorScheme(
    @ColorInt seed: Int,
    val darkTheme: Boolean,
    val style: Style = Style.TONAL_SPOT
) {

    val accent1: List<Int>
    val accent2: List<Int>
    val accent3: List<Int>
    val neutral1: List<Int>
    val neutral2: List<Int>

    constructor(@ColorInt seed: Int, darkTheme: Boolean):
            this(seed, darkTheme, Style.TONAL_SPOT)

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
        val proposedSeedCam = Cam.fromInt(seed)
        val seedArgb = if (seed == Color.TRANSPARENT) {
            GOOGLE_BLUE
        } else if (proposedSeedCam.chroma < 5) {
            GOOGLE_BLUE
        } else {
            seed
        }
        val camSeed = Cam.fromInt(seedArgb)
        accent1 = style.coreSpec.a1.shades(camSeed)
        accent2 = style.coreSpec.a2.shades(camSeed)
        accent3 = style.coreSpec.a3.shades(camSeed)
        neutral1 = style.coreSpec.n1.shades(camSeed)
        neutral2 = style.coreSpec.n2.shades(camSeed)
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
                    Cam.fromInt(it).chroma >= MIN_CHROMA
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
                cam.chroma >= MIN_CHROMA &&
                        (totalPopulationMeaningless || proportion > 0.01)
            }
            // Sort the colors by score, from high to low.
            val intToScoreIntermediate = filteredIntToCam.mapValues {
                score(it.value, intToHueProportion[it.key]!!)
            }
            val intToScore = intToScoreIntermediate.entries.toMutableList()
            intToScore.sortByDescending { it.value }

            // Go through the colors, from high score to low score.
            // If the color is distinct in hue from colors picked so far, pick the color.
            // Iteratively decrease the amount of hue distinctness required, thus ensuring we
            // maximize difference between colors.
            val minimumHueDistance = 15
            val seeds = mutableListOf<Int>()
            maximizeHueDistance@ for (i in 90 downTo minimumHueDistance step 1) {
                seeds.clear()
                for (entry in intToScore) {
                    val int = entry.key
                    val existingSeedNearby = seeds.find {
                        val hueA = intToCam[int]!!.hue
                        val hueB = intToCam[it]!!.hue
                        hueDiff(hueA, hueB) < i } != null
                    if (existingSeedNearby) {
                        continue
                    }
                    seeds.add(int)
                    if (seeds.size >= 4) {
                        break@maximizeHueDistance
                    }
                }
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

        public fun wrapDegreesDouble(degrees: Double): Double {
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
                if (cam.chroma <= MIN_CHROMA) {
                    continue
                }
                huePopulation[hue] = huePopulation[hue] + population
            }

            return huePopulation
        }
    }
}
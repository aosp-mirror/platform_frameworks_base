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

internal interface Hue {
    fun get(sourceColor: Cam): Double

    /**
     * Given a hue, and a mapping of hues to hue rotations, find which hues in the mapping the
     * hue fall betweens, and use the hue rotation of the lower hue.
     *
     * @param sourceHue hue of source color
     * @param hueAndRotations list of pairs, where the first item in a pair is a hue, and the
     *    second item in the pair is a hue rotation that should be applied
     */
    fun getHueRotation(sourceHue: Float, hueAndRotations: List<Pair<Int, Int>>): Double {
        for (i in 0..hueAndRotations.size) {
            val previousIndex = if (i == 0) hueAndRotations.size - 1 else i - 1
            val thisHue = hueAndRotations[i].first
            val previousHue = hueAndRotations[previousIndex].first
            if (ColorScheme.angleIsBetween(sourceHue, thisHue, previousHue)) {
                return ColorScheme.wrapDegreesDouble(sourceHue.toDouble() +
                        hueAndRotations[previousIndex].first)
            }
        }

        // If this statement executes, something is wrong, there should have been a rotation
        // found using the arrays.
        return sourceHue.toDouble()
    }
}

internal class HueSource : Hue {
    override fun get(sourceColor: Cam): Double {
        return sourceColor.hue.toDouble()
    }
}

internal class HueAdd(val amountDegrees: Double) : Hue {
    override fun get(sourceColor: Cam): Double {
        return ColorScheme.wrapDegreesDouble(sourceColor.hue.toDouble() + amountDegrees)
    }
}

internal class HueSubtract(val amountDegrees: Double) : Hue {
    override fun get(sourceColor: Cam): Double {
        return ColorScheme.wrapDegreesDouble(sourceColor.hue.toDouble() - amountDegrees)
    }
}

internal class HueVibrantSecondary() : Hue {
    val hueToRotations = listOf(Pair(24, 15), Pair(53, 15), Pair(91, 15), Pair(123, 15),
            Pair(141, 15), Pair(172, 15), Pair(198, 15), Pair(234, 18), Pair(272, 18),
            Pair(302, 18), Pair(329, 30), Pair(354, 15))
    override fun get(sourceColor: Cam): Double {
        return getHueRotation(sourceColor.hue, hueToRotations)
    }
}

internal class HueVibrantTertiary() : Hue {
    val hueToRotations = listOf(Pair(24, 30), Pair(53, 30), Pair(91, 15), Pair(123, 30),
            Pair(141, 27), Pair(172, 27), Pair(198, 30), Pair(234, 35), Pair(272, 30),
            Pair(302, 30), Pair(329, 60), Pair(354, 30))
    override fun get(sourceColor: Cam): Double {
        return getHueRotation(sourceColor.hue, hueToRotations)
    }
}

internal class HueExpressiveSecondary() : Hue {
    val hueToRotations = listOf(Pair(24, 95), Pair(53, 45), Pair(91, 45), Pair(123, 20),
            Pair(141, 45), Pair(172, 45), Pair(198, 15), Pair(234, 15),
            Pair(272, 45), Pair(302, 45), Pair(329, 45), Pair(354, 45))
    override fun get(sourceColor: Cam): Double {
        return getHueRotation(sourceColor.hue, hueToRotations)
    }
}

internal class HueExpressiveTertiary() : Hue {
    val hueToRotations = listOf(Pair(24, 20), Pair(53, 20), Pair(91, 20), Pair(123, 45),
            Pair(141, 20), Pair(172, 20), Pair(198, 90), Pair(234, 90), Pair(272, 20),
            Pair(302, 20), Pair(329, 120), Pair(354, 120))
    override fun get(sourceColor: Cam): Double {
        return getHueRotation(sourceColor.hue, hueToRotations)
    }
}

internal interface Chroma {
    fun get(sourceColor: Cam): Double

    /**
     * Given a hue, and a mapping of hues to hue rotations, find which hues in the mapping the
     * hue fall betweens, and use the hue rotation of the lower hue.
     *
     * @param sourceHue hue of source color
     * @param hueAndChromas list of pairs, where the first item in a pair is a hue, and the
     *    second item in the pair is a chroma that should be applied
     */
    fun getSpecifiedChroma(sourceHue: Float, hueAndChromas: List<Pair<Int, Int>>): Double {
        for (i in 0..hueAndChromas.size) {
            val previousIndex = if (i == 0) hueAndChromas.size - 1 else i - 1
            val thisHue = hueAndChromas[i].first
            val previousHue = hueAndChromas[previousIndex].first
            if (ColorScheme.angleIsBetween(sourceHue, thisHue, previousHue)) {
                return hueAndChromas[i].second.toDouble()
            }
        }

        // If this statement executes, something is wrong, there should have been a rotation
        // found using the arrays.
        return sourceHue.toDouble()
    }
}

internal class ChromaConstant(val chroma: Double) : Chroma {
    override fun get(sourceColor: Cam): Double {
        return chroma
    }
}

internal class ChromaExpressiveNeutral() : Chroma {
    val hueToChromas = listOf(Pair(24, 8), Pair(53, 8), Pair(91, 8), Pair(123, 8),
            Pair(141, 6), Pair(172, 6), Pair(198, 8), Pair(234, 8), Pair(272, 8),
            Pair(302, 8), Pair(329, 8), Pair(354, 8))
    override fun get(sourceColor: Cam): Double {
        return getSpecifiedChroma(sourceColor.hue, hueToChromas)
    }
}

internal class TonalSpec(val hue: Hue = HueSource(), val chroma: Chroma) {
    fun shades(sourceColor: Cam): List<Int> {
        val hue = hue.get(sourceColor)
        val chroma = chroma.get(sourceColor)
        return Shades.of(hue.toFloat(), chroma.toFloat()).toList()
    }
}

internal class CoreSpec(
    val a1: TonalSpec,
    val a2: TonalSpec,
    val a3: TonalSpec,
    val n1: TonalSpec,
    val n2: TonalSpec
)

enum class Style(internal val coreSpec: CoreSpec) {
    SPRITZ(CoreSpec(
            a1 = TonalSpec(HueSource(), ChromaConstant(12.0)),
            a2 = TonalSpec(HueSource(), ChromaConstant(8.0)),
            a3 = TonalSpec(HueSource(), ChromaConstant(16.0)),
            n1 = TonalSpec(HueSource(), ChromaConstant(2.0)),
            n2 = TonalSpec(HueSource(), ChromaConstant(2.0))
    )),
    TONAL_SPOT(CoreSpec(
            a1 = TonalSpec(HueSource(), ChromaConstant(36.0)),
            a2 = TonalSpec(HueSource(), ChromaConstant(16.0)),
            a3 = TonalSpec(HueAdd(60.0), ChromaConstant(24.0)),
            n1 = TonalSpec(HueSource(), ChromaConstant(4.0)),
            n2 = TonalSpec(HueSource(), ChromaConstant(8.0))
    )),
    VIBRANT(CoreSpec(
            a1 = TonalSpec(HueSource(), ChromaConstant(48.0)),
            a2 = TonalSpec(HueVibrantSecondary(), ChromaConstant(24.0)),
            a3 = TonalSpec(HueVibrantTertiary(), ChromaConstant(32.0)),
            n1 = TonalSpec(HueSource(), ChromaConstant(6.0)),
            n2 = TonalSpec(HueSource(), ChromaConstant(12.0))
    )),
    EXPRESSIVE(CoreSpec(
            a1 = TonalSpec(HueAdd(240.0), ChromaConstant(40.0)),
            a2 = TonalSpec(HueExpressiveSecondary(), ChromaConstant(24.0)),
            a3 = TonalSpec(HueExpressiveTertiary(), ChromaConstant(40.0)),
            n1 = TonalSpec(HueAdd(15.0), ChromaExpressiveNeutral()),
            n2 = TonalSpec(HueAdd(15.0), ChromaConstant(12.0))
    )),
    RAINBOW(CoreSpec(
            a1 = TonalSpec(HueSource(), ChromaConstant(48.0)),
            a2 = TonalSpec(HueSource(), ChromaConstant(16.0)),
            a3 = TonalSpec(HueAdd(60.0), ChromaConstant(24.0)),
            n1 = TonalSpec(HueSource(), ChromaConstant(0.0)),
            n2 = TonalSpec(HueSource(), ChromaConstant(0.0))
    )),
    FRUIT_SALAD(CoreSpec(
            a1 = TonalSpec(HueSubtract(50.0), ChromaConstant(48.0)),
            a2 = TonalSpec(HueSubtract(50.0), ChromaConstant(36.0)),
            a3 = TonalSpec(HueSource(), ChromaConstant(36.0)),
            n1 = TonalSpec(HueSource(), ChromaConstant(10.0)),
            n2 = TonalSpec(HueSource(), ChromaConstant(16.0))
    )),
}

class ColorScheme(
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
                "  style: $style\n" +
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

        internal fun angleIsBetween(angle: Float, a: Int, b: Int): Boolean {
            if (a < b) {
                return a <= angle && angle <= b
            }
            return a <= angle || angle <= b
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
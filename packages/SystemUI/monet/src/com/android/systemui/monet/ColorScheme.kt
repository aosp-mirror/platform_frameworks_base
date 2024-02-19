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
import com.google.ux.material.libmonet.hct.Hct
import com.google.ux.material.libmonet.scheme.DynamicScheme
import com.google.ux.material.libmonet.scheme.SchemeContent
import com.google.ux.material.libmonet.scheme.SchemeExpressive
import com.google.ux.material.libmonet.scheme.SchemeFruitSalad
import com.google.ux.material.libmonet.scheme.SchemeMonochrome
import com.google.ux.material.libmonet.scheme.SchemeNeutral
import com.google.ux.material.libmonet.scheme.SchemeRainbow
import com.google.ux.material.libmonet.scheme.SchemeTonalSpot
import com.google.ux.material.libmonet.scheme.SchemeVibrant
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

const val TAG = "ColorScheme"

const val ACCENT1_CHROMA = 48.0f
const val GOOGLE_BLUE = 0xFF1b6ef3.toInt()
const val MIN_CHROMA = 5

enum class Style{
    SPRITZ,
    TONAL_SPOT,
    VIBRANT,
    EXPRESSIVE,
    RAINBOW,
    FRUIT_SALAD,
    CONTENT,
    MONOCHROMATIC,
    CLOCK,
    CLOCK_VIBRANT
}

class TonalPalette
internal constructor(
    private val materialTonalPalette: com.google.ux.material.libmonet.palettes.TonalPalette
) {
    @Deprecated("Do not use. For color system only")
    val allShades: List<Int>
    val allShadesMapped: Map<Int, Int>

    init{
        allShades = SHADE_KEYS.map {key -> getAtTone(key.toFloat()) }
        allShadesMapped = SHADE_KEYS.zip(allShades).toMap()
    }

    // Dynamically computed tones across the full range from 0 to 1000
    fun getAtTone(shade: Float): Int = materialTonalPalette.tone(((1000.0f - shade) / 10f).toInt())

    // Predefined & precomputed tones
    val s0: Int
        get() = this.allShades[0]
    val s10: Int
        get() = this.allShades[1]
    val s50: Int
        get() = this.allShades[2]
    val s100: Int
        get() = this.allShades[3]
    val s200: Int
        get() = this.allShades[4]
    val s300: Int
        get() = this.allShades[5]
    val s400: Int
        get() = this.allShades[6]
    val s500: Int
        get() = this.allShades[7]
    val s600: Int
        get() = this.allShades[8]
    val s700: Int
        get() = this.allShades[9]
    val s800: Int
        get() = this.allShades[10]
    val s900: Int
        get() = this.allShades[11]
    val s1000: Int
        get() = this.allShades[12]

    companion object {
        val SHADE_KEYS = listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
    }
}

@Deprecated("Please use com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors " +
        "instead")
class ColorScheme(
    @ColorInt val seed: Int,
    val isDark: Boolean,
    val style: Style,
    val contrastLevel: Double
) {
    var materialScheme: DynamicScheme

    private val proposedSeedHct: Hct = Hct.fromInt(seed)
    private val seedHct: Hct = Hct.fromInt(if (seed == Color.TRANSPARENT) {
        GOOGLE_BLUE
    } else if (style != Style.CONTENT && proposedSeedHct.chroma < 5) {
        GOOGLE_BLUE
    } else {
        seed
    })

    val accent1: TonalPalette
    val accent2: TonalPalette
    val accent3: TonalPalette
    val neutral1: TonalPalette
    val neutral2: TonalPalette

    constructor(@ColorInt seed: Int, darkTheme: Boolean) : this(seed, darkTheme, Style.TONAL_SPOT)

    @JvmOverloads
    constructor(
        @ColorInt seed: Int,
        darkTheme: Boolean,
        style: Style
    ) : this(seed, darkTheme, style, 0.0)

    @JvmOverloads
    constructor(
        wallpaperColors: WallpaperColors,
        darkTheme: Boolean,
        style: Style = Style.TONAL_SPOT
    ) : this(getSeedColor(wallpaperColors, style != Style.CONTENT), darkTheme, style)

    val backgroundColor
        get() = ColorUtils.setAlphaComponent(if (isDark) neutral1.s700 else neutral1.s10, 0xFF)

    val accentColor
        get() = ColorUtils.setAlphaComponent(if (isDark) accent1.s100 else accent1.s500, 0xFF)

    init {
        materialScheme = when (style) {
            Style.SPRITZ -> SchemeNeutral(seedHct, isDark, contrastLevel)
            Style.TONAL_SPOT -> SchemeTonalSpot(seedHct, isDark, contrastLevel)
            Style.VIBRANT -> SchemeVibrant(seedHct, isDark, contrastLevel)
            Style.EXPRESSIVE -> SchemeExpressive(seedHct, isDark, contrastLevel)
            Style.RAINBOW -> SchemeRainbow(seedHct, isDark, contrastLevel)
            Style.FRUIT_SALAD -> SchemeFruitSalad(seedHct, isDark, contrastLevel)
            Style.CONTENT -> SchemeContent(seedHct, isDark, contrastLevel)
            Style.MONOCHROMATIC -> SchemeMonochrome(seedHct, isDark, contrastLevel)

            // SystemUI Schemes
            Style.CLOCK -> SchemeClock(seedHct, isDark, contrastLevel)
            Style.CLOCK_VIBRANT -> SchemeClockVibrant(seedHct, isDark, contrastLevel)
        }

        accent1 = TonalPalette(materialScheme.primaryPalette)
        accent2 = TonalPalette(materialScheme.secondaryPalette)
        accent3 = TonalPalette(materialScheme.tertiaryPalette)
        neutral1 = TonalPalette(materialScheme.neutralPalette)
        neutral2 = TonalPalette(materialScheme.neutralVariantPalette)
    }

    val seedTone: Float
        get() = 1000f - proposedSeedHct.tone.toFloat() * 10f

    override fun toString(): String {
        return "ColorScheme {\n" +
            "  seed color: ${stringForColor(seed)}\n" +
            "  style: $style\n" +
            "  palettes: \n" +
            "  ${humanReadable("PRIMARY", accent1.allShades)}\n" +
            "  ${humanReadable("SECONDARY", accent2.allShades)}\n" +
            "  ${humanReadable("TERTIARY", accent3.allShades)}\n" +
            "  ${humanReadable("NEUTRAL", neutral1.allShades)}\n" +
            "  ${humanReadable("NEUTRAL VARIANT", neutral2.allShades)}\n" +
            "}"
    }

    companion object {
        /**
         * Identifies a color to create a color scheme from.
         *
         * @param wallpaperColors Colors extracted from an image via quantization.
         * @param filter If false, allow colors that have low chroma, creating grayscale themes.
         * @return ARGB int representing the color
         */
        @JvmStatic
        @JvmOverloads
        @ColorInt
        fun getSeedColor(wallpaperColors: WallpaperColors, filter: Boolean = true): Int {
            return getSeedColors(wallpaperColors, filter).first()
        }

        /**
         * Filters and ranks colors from WallpaperColors.
         *
         * @param wallpaperColors Colors extracted from an image via quantization.
         * @param filter If false, allow colors that have low chroma, creating grayscale themes.
         * @return List of ARGB ints, ordered from highest scoring to lowest.
         */
        @JvmStatic
        @JvmOverloads
        fun getSeedColors(wallpaperColors: WallpaperColors, filter: Boolean = true): List<Int> {
            val totalPopulation =
                wallpaperColors.allColors.values.reduce { a, b -> a + b }.toDouble()
            val totalPopulationMeaningless = (totalPopulation == 0.0)
            if (totalPopulationMeaningless) {
                // WallpaperColors with a population of 0 indicate the colors didn't come from
                // quantization. Instead of scoring, trust the ordering of the provided primary
                // secondary/tertiary colors.
                //
                // In this case, the colors are usually from a Live Wallpaper.
                val distinctColors =
                    wallpaperColors.mainColors
                        .map { it.toArgb() }
                        .distinct()
                        .filter {
                            if (!filter) {
                                true
                            } else {
                                Hct.fromInt(it).chroma >= MIN_CHROMA
                            }
                        }
                        .toList()
                if (distinctColors.isEmpty()) {
                    return listOf(GOOGLE_BLUE)
                }
                return distinctColors
            }

            val intToProportion =
                wallpaperColors.allColors.mapValues { it.value.toDouble() / totalPopulation }
            val intToHct = wallpaperColors.allColors.mapValues { Hct.fromInt(it.key) }

            // Get an array with 360 slots. A slot contains the percentage of colors with that hue.
            val hueProportions = huePopulations(intToHct, intToProportion, filter)
            // Map each color to the percentage of the image with its hue.
            val intToHueProportion =
                wallpaperColors.allColors.mapValues {
                    val hct = intToHct[it.key]!!
                    val hue = hct.hue.roundToInt()
                    var proportion = 0.0
                    for (i in hue - 15..hue + 15) {
                        proportion += hueProportions[wrapDegrees(i)]
                    }
                    proportion
                }
            // Remove any inappropriate seed colors. For example, low chroma colors look grayscale
            // raising their chroma will turn them to a much louder color that may not have been
            // in the image.
            val filteredIntToHct =
                if (!filter) intToHct
                else
                    (intToHct.filter {
                        val hct = it.value
                        val proportion = intToHueProportion[it.key]!!
                        hct.chroma >= MIN_CHROMA &&
                            (totalPopulationMeaningless || proportion > 0.01)
                    })
            // Sort the colors by score, from high to low.
            val intToScoreIntermediate =
                filteredIntToHct.mapValues { score(it.value, intToHueProportion[it.key]!!) }
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
                    val existingSeedNearby =
                        seeds.find {
                            val hueA = intToHct[int]!!.hue
                            val hueB = intToHct[it]!!.hue
                            hueDiff(hueA, hueB) < i
                        } != null
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

        private fun hueDiff(a: Double, b: Double): Double {
            return 180f - ((a - b).absoluteValue - 180f).absoluteValue
        }

        private fun stringForColor(color: Int): String {
            val width = 4
            val hct = Hct.fromInt(color)
            val h = "H${hct.hue.roundToInt().toString().padEnd(width)}"
            val c = "C${hct.chroma.roundToInt().toString().padEnd(width)}"
            val t = "T${hct.tone.roundToInt().toString().padEnd(width)}"
            val hex = Integer.toHexString(color and 0xffffff).padStart(6, '0').uppercase()
            return "$h$c$t = #$hex"
        }

        private fun humanReadable(paletteName: String, colors: List<Int>): String {
            return "$paletteName\n" +
                colors.map { stringForColor(it) }.joinToString(separator = "\n") { it }
        }

        private fun score(hct: Hct, proportion: Double): Double {
            val proportionScore = 0.7 * 100.0 * proportion
            val chromaScore =
                if (hct.chroma < ACCENT1_CHROMA) 0.1 * (hct.chroma - ACCENT1_CHROMA)
                else 0.3 * (hct.chroma - ACCENT1_CHROMA)
            return chromaScore + proportionScore
        }

        private fun huePopulations(
            hctByColor: Map<Int, Hct>,
            populationByColor: Map<Int, Double>,
            filter: Boolean = true
        ): List<Double> {
            val huePopulation = List(size = 360, init = { 0.0 }).toMutableList()

            for (entry in populationByColor.entries) {
                val population = populationByColor[entry.key]!!
                val hct = hctByColor[entry.key]!!
                val hue = hct.hue.roundToInt() % 360
                if (filter && hct.chroma <= MIN_CHROMA) {
                    continue
                }
                huePopulation[hue] = huePopulation[hue] + population
            }

            return huePopulation
        }
    }
}

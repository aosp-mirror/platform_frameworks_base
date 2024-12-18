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

package com.android.systemui.animation

import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.util.Log
import android.util.LruCache
import android.util.MathUtils
import androidx.annotation.VisibleForTesting
import java.lang.Float.max
import java.lang.Float.min

private const val TAG_WGHT = "wght"
private const val TAG_ITAL = "ital"

private const val FONT_WEIGHT_DEFAULT_VALUE = 400f
private const val FONT_ITALIC_MAX = 1f
private const val FONT_ITALIC_MIN = 0f
private const val FONT_ITALIC_ANIMATION_STEP = 0.1f
private const val FONT_ITALIC_DEFAULT_VALUE = 0f

/** Caches for font interpolation */
interface FontCache {
    val animationFrameCount: Int

    fun get(key: InterpKey): Font?

    fun get(key: VarFontKey): Font?

    fun put(key: InterpKey, font: Font)

    fun put(key: VarFontKey, font: Font)
}

/** Cache key for the interpolated font. */
data class InterpKey(val start: Font?, val end: Font?, val frame: Int)

/** Cache key for the font that has variable font. */
data class VarFontKey(val sourceId: Int, val index: Int, val sortedAxes: List<FontVariationAxis>) {
    constructor(
        font: Font,
        axes: List<FontVariationAxis>,
    ) : this(font.sourceIdentifier, font.ttcIndex, axes.sortedBy { it.tag })
}

class FontCacheImpl(override val animationFrameCount: Int = DEFAULT_FONT_CACHE_MAX_ENTRIES / 2) :
    FontCache {
    // Font interpolator has two level caches: one for input and one for font with different
    // variation settings. No synchronization is needed since FontInterpolator is not designed to be
    // thread-safe and can be used only on UI thread.
    val cacheMaxEntries = animationFrameCount * 2
    private val interpCache = LruCache<InterpKey, Font>(cacheMaxEntries)
    private val verFontCache = LruCache<VarFontKey, Font>(cacheMaxEntries)

    override fun get(key: InterpKey): Font? = interpCache[key]

    override fun get(key: VarFontKey): Font? = verFontCache[key]

    override fun put(key: InterpKey, font: Font) {
        interpCache.put(key, font)
    }

    override fun put(key: VarFontKey, font: Font) {
        verFontCache.put(key, font)
    }

    companion object {
        // Benchmarked via Perfetto, difference between 10 and 50 entries is about 0.3ms in frame
        // draw time on a Pixel 6.
        @VisibleForTesting const val DEFAULT_FONT_CACHE_MAX_ENTRIES = 10
    }
}

/** Provide interpolation of two fonts by adjusting font variation settings. */
class FontInterpolator(val fontCache: FontCache = FontCacheImpl()) {
    /** Linear interpolate the font variation settings. */
    fun lerp(start: Font, end: Font, progress: Float): Font {
        if (progress == 0f) {
            return start
        } else if (progress == 1f) {
            return end
        }

        val startAxes = start.axes ?: EMPTY_AXES
        val endAxes = end.axes ?: EMPTY_AXES

        if (startAxes.isEmpty() && endAxes.isEmpty()) {
            return start
        }

        // Check we already know the result. This is commonly happens since we draws the different
        // text chunks with the same font.
        val iKey = InterpKey(start, end, (progress * fontCache.animationFrameCount).toInt())
        fontCache.get(iKey)?.let {
            if (DEBUG) {
                Log.d(LOG_TAG, "[$progress] Interp. cache hit for $iKey")
            }
            return it
        }

        // General axes interpolation takes O(N log N), this is came from sorting the axes. Usually
        // this doesn't take much time since the variation axes is usually up to 5. If we need to
        // support more number of axes, we may want to preprocess the font and store the sorted axes
        // and also pre-fill the missing axes value with default value from 'fvar' table.
        val newAxes =
            lerp(startAxes, endAxes) { tag, startValue, endValue ->
                when (tag) {
                    TAG_WGHT ->
                        MathUtils.lerp(
                            startValue ?: FONT_WEIGHT_DEFAULT_VALUE,
                            endValue ?: FONT_WEIGHT_DEFAULT_VALUE,
                            progress,
                        )
                    TAG_ITAL ->
                        adjustItalic(
                            MathUtils.lerp(
                                startValue ?: FONT_ITALIC_DEFAULT_VALUE,
                                endValue ?: FONT_ITALIC_DEFAULT_VALUE,
                                progress,
                            )
                        )
                    else -> {
                        require(startValue != null && endValue != null) {
                            "Unable to interpolate due to unknown default axes value : $tag"
                        }
                        MathUtils.lerp(startValue, endValue, progress)
                    }
                }
            }

        // Check if we already make font for this axes. This is typically happens if the animation
        // happens backward.
        val vKey = VarFontKey(start, newAxes)
        fontCache.get(vKey)?.let {
            fontCache.put(iKey, it)
            if (DEBUG) {
                Log.d(LOG_TAG, "[$progress] Axis cache hit for $vKey")
            }
            return it
        }

        // This is the first time to make the font for the axes. Build and store it to the cache.
        // Font.Builder#build won't throw IOException since creating fonts from existing fonts will
        // not do any IO work.
        val newFont = Font.Builder(start).setFontVariationSettings(newAxes.toTypedArray()).build()
        fontCache.put(iKey, newFont)
        fontCache.put(vKey, newFont)

        // Cache misses are likely to create memory leaks, so this is logged at error level.
        Log.e(LOG_TAG, "[$progress] Cache MISS for $iKey / $vKey")
        return newFont
    }

    private fun lerp(
        start: Array<FontVariationAxis>,
        end: Array<FontVariationAxis>,
        filter: (tag: String, left: Float?, right: Float?) -> Float,
    ): List<FontVariationAxis> {
        // Safe to modify result of Font#getAxes since it returns cloned object.
        start.sortBy { axis -> axis.tag }
        end.sortBy { axis -> axis.tag }

        val result = mutableListOf<FontVariationAxis>()
        var i = 0
        var j = 0
        while (i < start.size || j < end.size) {
            val tagA = if (i < start.size) start[i].tag else null
            val tagB = if (j < end.size) end[j].tag else null

            val comp =
                when {
                    tagA == null -> 1
                    tagB == null -> -1
                    else -> tagA.compareTo(tagB)
                }

            val axis =
                when {
                    comp == 0 -> {
                        val v = filter(tagA!!, start[i++].styleValue, end[j++].styleValue)
                        FontVariationAxis(tagA, v)
                    }
                    comp < 0 -> {
                        val v = filter(tagA!!, start[i++].styleValue, null)
                        FontVariationAxis(tagA, v)
                    }
                    else -> { // comp > 0
                        val v = filter(tagB!!, null, end[j++].styleValue)
                        FontVariationAxis(tagB, v)
                    }
                }

            result.add(axis)
        }
        return result
    }

    // For the performance reasons, we animate italic with FONT_ITALIC_ANIMATION_STEP. This helps
    // Cache hit ratio in the Skia glyph cache.
    private fun adjustItalic(value: Float) =
        coerceInWithStep(value, FONT_ITALIC_MIN, FONT_ITALIC_MAX, FONT_ITALIC_ANIMATION_STEP)

    private fun coerceInWithStep(v: Float, min: Float, max: Float, step: Float) =
        (v.coerceIn(min, max) / step).toInt() * step

    companion object {
        private const val LOG_TAG = "FontInterpolator"
        private val DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG)
        private val EMPTY_AXES = arrayOf<FontVariationAxis>()

        // Returns true if given two font instance can be interpolated.
        fun canInterpolate(start: Font, end: Font) =
            start.ttcIndex == end.ttcIndex && start.sourceIdentifier == end.sourceIdentifier
    }
}

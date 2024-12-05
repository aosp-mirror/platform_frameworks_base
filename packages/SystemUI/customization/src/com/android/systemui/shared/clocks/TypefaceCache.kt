/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shared.clocks

import android.graphics.Typeface
import com.android.systemui.animation.FontCacheImpl
import com.android.systemui.animation.TypefaceVariantCache
import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

class TypefaceCache(
    messageBuffer: MessageBuffer,
    val animationFrameCount: Int,
    val typefaceFactory: (String) -> Typeface,
) {
    private val logger = Logger(messageBuffer, this::class.simpleName!!)

    private data class CacheKey(val res: String, val fvar: String?)

    private inner class WeakTypefaceRef(val key: CacheKey, typeface: Typeface) :
        WeakReference<Typeface>(typeface, queue)

    private var totalHits = 0

    private var totalMisses = 0

    private var totalEvictions = 0

    // We use a map of WeakRefs here instead of an LruCache. This prevents needing to resize the
    // cache depending on the number of distinct fonts used by a clock, as different clocks have
    // different numbers of simultaneously loaded and configured fonts. Because our clocks tend to
    // initialize a number of parallel views and animators, our usages of Typefaces overlap. As a
    // result, once a typeface is no longer being used, it is unlikely to be recreated immediately.
    private val cache = mutableMapOf<CacheKey, WeakTypefaceRef>()
    private val queue = ReferenceQueue<Typeface>()
    private val fontCache = FontCacheImpl(animationFrameCount)

    fun getTypeface(res: String): Typeface {
        checkQueue()
        val key = CacheKey(res, null)
        cache.get(key)?.get()?.let {
            logHit(key)
            return it
        }

        logMiss(key)
        val result = typefaceFactory(res)
        cache.put(key, WeakTypefaceRef(key, result))
        return result
    }

    fun getVariantCache(res: String): TypefaceVariantCache {
        val baseTypeface = getTypeface(res)
        return object : TypefaceVariantCache {
            override val fontCache = this@TypefaceCache.fontCache
            override val animationFrameCount = this@TypefaceCache.animationFrameCount

            override fun getTypefaceForVariant(fvar: String?): Typeface? {
                checkQueue()
                val key = CacheKey(res, fvar)
                cache.get(key)?.get()?.let {
                    logHit(key)
                    return it
                }

                logMiss(key)
                return TypefaceVariantCache.createVariantTypeface(baseTypeface, fvar).also {
                    cache.put(key, WeakTypefaceRef(key, it))
                }
            }
        }
    }

    private fun logHit(key: CacheKey) {
        totalHits++
        if (DEBUG_HITS)
            logger.i({ "HIT: $str1; Total: $int1" }) {
                str1 = key.toString()
                int1 = totalHits
            }
    }

    private fun logMiss(key: CacheKey) {
        totalMisses++
        logger.w({ "MISS: $str1; Total: $int1" }) {
            str1 = key.toString()
            int1 = totalMisses
        }
    }

    private fun logEviction(key: CacheKey) {
        totalEvictions++
        logger.i({ "EVICTED: $str1; Total: $int1" }) {
            str1 = key.toString()
            int1 = totalEvictions
        }
    }

    private fun checkQueue() =
        generateSequence { queue.poll() }
            .filterIsInstance<WeakTypefaceRef>()
            .forEach {
                logEviction(it.key)
                cache.remove(it.key)
            }

    companion object {
        private val DEBUG_HITS = false
    }
}

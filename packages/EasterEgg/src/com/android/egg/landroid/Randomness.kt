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

package com.android.egg.landroid

import kotlin.random.Random

/**
 * A bag of stones. Each time you pull one out it is not replaced, preventing duplicates. When the
 * bag is exhausted, all the stones are replaced and reshuffled.
 */
class Bag<T>(items: Array<T>) {
    private val remaining = items.copyOf()
    private var next = remaining.size // will cause a shuffle on first pull()

    /** Return the next random item from the bag, without replacing it. */
    fun pull(rng: Random): T {
        if (next >= remaining.size) {
            remaining.shuffle(rng)
            next = 0
        }
        return remaining[next++]
    }
}

/**
 * A loot table. The weight of each possibility is in the first of the pair; the value to be
 * returned in the second. They need not add up to 1f (we will do that for you, free of charge).
 */
class RandomTable<T>(private vararg val pairs: Pair<Float, T>) {
    private val total = pairs.map { it.first }.sum()

    /** Select a random value from the weighted table. */
    fun roll(rng: Random): T {
        var x = rng.nextFloatInRange(0f, total)
        for ((weight, result) in pairs) {
            x -= weight
            if (x < 0f) return result
        }
        return pairs.last().second
    }
}

/** Return a random float in the range [from, until). */
fun Random.nextFloatInRange(from: Float, until: Float): Float =
    from + ((until - from) * nextFloat())

/** Return a random float in the range [start, end). */
fun Random.nextFloatInRange(fromUntil: ClosedFloatingPointRange<Float>): Float =
    nextFloatInRange(fromUntil.start, fromUntil.endInclusive)
/** Return a random float in the range [first, second). */
fun Random.nextFloatInRange(fromUntil: Pair<Float, Float>): Float =
    nextFloatInRange(fromUntil.first, fromUntil.second)

/** Choose a random element from an array. */
fun <T> Random.choose(array: Array<T>) = array[nextInt(array.size)]

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

package com.android.systemui.util

import android.graphics.Rect
import android.util.IndentingPrintWriter
import android.view.View
import android.view.ViewGroup
import dagger.Lazy
import java.io.PrintWriter

/** [Sequence] that yields all of the direct children of this [ViewGroup] */
val ViewGroup.children
    get() = sequence { for (i in 0 until childCount) yield(getChildAt(i)) }

/** Inclusive version of [Iterable.takeWhile] */
fun <T> Sequence<T>.takeUntil(pred: (T) -> Boolean): Sequence<T> = sequence {
    for (x in this@takeUntil) {
        yield(x)
        if (pred(x)) {
            break
        }
    }
}

/**
 * If `this` is an [IndentingPrintWriter], it will process block inside an indentation level.
 *
 * If not, this will just process block.
 */
inline fun PrintWriter.indentIfPossible(block: PrintWriter.() -> Unit) {
    if (this is IndentingPrintWriter) increaseIndent()
    block()
    if (this is IndentingPrintWriter) decreaseIndent()
}

/** Convenience extension property for [View.getBoundsOnScreen]. */
val View.boundsOnScreen: Rect
    get() {
        val bounds = Rect()
        getBoundsOnScreen(bounds)
        return bounds
    }

/** Extension method to convert [dagger.Lazy] to [kotlin.Lazy] for object of any class [T]. */
fun <T> Lazy<T>.toKotlinLazy(): kotlin.Lazy<T> {
    return lazy { this.get() }
}

/**
 * Returns whether this [Collection] contains exactly all [elements].
 *
 * Order of elements is not taken into account, but multiplicity is. For example, an element
 * duplicated exactly 3 times in the parameter asserts that the element must likewise be duplicated
 * exactly 3 times in this [Collection].
 */
fun <T> Collection<T>.containsExactly(vararg elements: T): Boolean {
    return eachCountMap() == elements.asList().eachCountMap()
}

/**
 * Returns a map where keys are the distinct elements of the collection and values are their
 * corresponding counts.
 *
 * This is a convenient extension function for any [Collection] that allows you to easily count the
 * occurrences of each element.
 */
fun <T> Collection<T>.eachCountMap(): Map<T, Int> {
    return groupingBy { it }.eachCount()
}

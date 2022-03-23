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

package com.android.systemui.util

import android.util.IndentingPrintWriter
import android.view.View
import java.io.PrintWriter
import java.util.function.Consumer

/**
 * Run some code that will print to an [IndentingPrintWriter] that wraps the given [PrintWriter].
 *
 * If the given [PrintWriter] is an [IndentingPrintWriter], the block will be passed that same
 * instance with [IndentingPrintWriter.increaseIndent] having been called, and calling
 * [IndentingPrintWriter.decreaseIndent] after completion of the block, so the passed [PrintWriter]
 * should not be used before the block completes.
 */
inline fun PrintWriter.withIndenting(block: (IndentingPrintWriter) -> Unit) {
    if (this is IndentingPrintWriter) {
        this.withIncreasedIndent { block(this) }
    } else {
        block(IndentingPrintWriter(this))
    }
}

/**
 * Run some code that will print to an [IndentingPrintWriter] that wraps the given [PrintWriter].
 *
 * If the given [PrintWriter] is an [IndentingPrintWriter], the block will be passed that same
 * instance with [IndentingPrintWriter.increaseIndent] having been called, and calling
 * [IndentingPrintWriter.decreaseIndent] after completion of the block, so the passed [PrintWriter]
 * should not be used before the block completes.
 */
fun PrintWriter.withIndenting(consumer: Consumer<IndentingPrintWriter>) {
    if (this is IndentingPrintWriter) {
        this.withIncreasedIndent { consumer.accept(this) }
    } else {
        consumer.accept(IndentingPrintWriter(this))
    }
}

/**
 * Run some code inside a block, with [IndentingPrintWriter.increaseIndent] having been called on
 * the given argument, and calling [IndentingPrintWriter.decreaseIndent] after completion.
 */
inline fun IndentingPrintWriter.withIncreasedIndent(block: () -> Unit) {
    this.increaseIndent()
    try {
        block()
    } finally {
        this.decreaseIndent()
    }
}

/** Return a readable string for the visibility */
fun visibilityString(@View.Visibility visibility: Int): String = when (visibility) {
    View.GONE -> "gone"
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    else -> "unknown:$visibility"
}

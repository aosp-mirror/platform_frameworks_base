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

/**
 * Get an [IndentingPrintWriter] which either is or wraps the given [PrintWriter].
 *
 * The original [PrintWriter] should not be used until the returned [IndentingPrintWriter] is no
 * longer being used, to avoid inconsistent writing.
 */
fun PrintWriter.asIndenting(): IndentingPrintWriter =
    (this as? IndentingPrintWriter) ?: IndentingPrintWriter(this)

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

/**
 * Run some code inside a block, with [IndentingPrintWriter.increaseIndent] having been called on
 * the given argument, and calling [IndentingPrintWriter.decreaseIndent] after completion.
 */
fun IndentingPrintWriter.withIncreasedIndent(runnable: Runnable) {
    this.increaseIndent()
    try {
        runnable.run()
    } finally {
        this.decreaseIndent()
    }
}

/** Print a line which is '$label=$value' */
fun IndentingPrintWriter.println(label: String, value: Any) =
    append(label).append('=').println(value)

/** Return a readable string for the visibility */
fun visibilityString(@View.Visibility visibility: Int): String = when (visibility) {
    View.GONE -> "gone"
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    else -> "unknown:$visibility"
}

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

package com.android.systemui.statusbar.notification.row

import android.text.PrecomputedText
import android.text.Spannable
import android.util.Log
import android.widget.TextView

internal interface TextPrecomputer {
    /**
     * Creates PrecomputedText from given text and returns a runnable which sets precomputed text to
     * the textview on main thread.
     *
     * @param text text to be converted to PrecomputedText
     * @return Runnable that sets precomputed text on the main thread
     */
    fun precompute(
        textView: TextView,
        text: CharSequence?,
        logException: Boolean = true
    ): Runnable {
        val precomputedText: Spannable? =
            text?.let { PrecomputedText.create(it, textView.textMetricsParams) }

        return Runnable {
            try {
                textView.text = precomputedText
            } catch (exception: IllegalArgumentException) {
                if (logException) {
                    Log.wtf(
                        /* tag = */ TAG,
                        /* msg = */ "PrecomputedText setText failed for TextView:$textView",
                        /* tr = */ exception
                    )
                }
                textView.text = text
            }
        }
    }

    private companion object {
        private const val TAG = "TextPrecomputer"
    }
}

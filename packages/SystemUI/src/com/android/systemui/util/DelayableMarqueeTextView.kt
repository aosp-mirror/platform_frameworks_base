/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.R

class DelayableMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SafeMarqueeTextView(context, attrs, defStyleAttr, defStyleRes) {

    var marqueeDelay: Long = DEFAULT_MARQUEE_DELAY
    private var wantsMarquee = false
    private var marqueeBlocked = true

    private val enableMarquee = Runnable {
        if (wantsMarquee) {
            marqueeBlocked = false
            startMarquee()
        }
    }

    init {
        val typedArray = context.theme.obtainStyledAttributes(
                attrs,
                R.styleable.DelayableMarqueeTextView,
                defStyleAttr,
                defStyleRes
        )
        marqueeDelay = typedArray.getInteger(
                R.styleable.DelayableMarqueeTextView_marqueeDelay,
                DEFAULT_MARQUEE_DELAY.toInt()
        ).toLong()
        typedArray.recycle()
    }

    override fun startMarquee() {
        if (!isSelected) {
            return
        }
        wantsMarquee = true
        if (marqueeBlocked) {
            if (handler?.hasCallbacks(enableMarquee) == false) {
                postDelayed(enableMarquee, marqueeDelay)
            }
            return
        }
        super.startMarquee()
    }

    override fun stopMarquee() {
        handler?.removeCallbacks(enableMarquee)
        wantsMarquee = false
        marqueeBlocked = true
        super.stopMarquee()
    }

    companion object {
        const val DEFAULT_MARQUEE_DELAY = 2000L
    }
}

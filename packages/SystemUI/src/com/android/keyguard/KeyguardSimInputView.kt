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

package com.android.keyguard

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.graphics.drawable.DrawableCompat
import com.android.systemui.R

abstract class KeyguardSimInputView(context: Context, attrs: AttributeSet) :
    KeyguardPinBasedInputView(context, attrs) {
    private var simImageView: ImageView? = null
    private var disableESimButton: KeyguardEsimArea? = null

    override fun onFinishInflate() {
        simImageView = findViewById(R.id.keyguard_sim)
        disableESimButton = findViewById(R.id.keyguard_esim_area)
        super.onFinishInflate()
    }

    /** Set UI state based on whether there is a locked eSim card */
    fun setESimLocked(isESimLocked: Boolean, subId: Int) {
        disableESimButton?.setSubscriptionId(subId)
        disableESimButton?.visibility = if (isESimLocked) VISIBLE else GONE
        simImageView?.visibility = if (isESimLocked) GONE else VISIBLE
    }

    override fun reloadColors() {
        super.reloadColors()
        val customAttrs = intArrayOf(android.R.attr.textColorSecondary)
        val a = context.obtainStyledAttributes(customAttrs)
        val imageColor = a.getColor(0, 0)
        a.recycle()
        simImageView?.let {
            val wrappedDrawable = DrawableCompat.wrap(it.drawable)
            DrawableCompat.setTint(wrappedDrawable, imageColor)
        }
    }
}

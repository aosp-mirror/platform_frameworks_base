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
package com.android.systemui.biometrics

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.android.systemui.R

/**
 * View corresponding with udfps_fpm_other_view.xml
 */
class UdfpsFpmOtherView(
    context: Context,
    attrs: AttributeSet?
) : UdfpsAnimationView(context, attrs) {

    private val fingerprintDrawable: UdfpsFpDrawable = UdfpsFpDrawable(context)
    private lateinit var fingerprintView: ImageView

    override fun onFinishInflate() {
        fingerprintView = findViewById(R.id.udfps_fpm_other_fp_view)!!
        fingerprintView.setImageDrawable(fingerprintDrawable)
    }

    override fun getDrawable(): UdfpsDrawable = fingerprintDrawable
}

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

/**
 * View corresponding with udfps_fpm_empty_view.xml
 *
 * Currently doesn't draw anything.
 */
class UdfpsFpmEmptyView(
    context: Context,
    attrs: AttributeSet?
) : UdfpsAnimationView(context, attrs) {

    // Drawable isn't ever added to the view, so we don't currently show anything
    private val fingerprintDrawable: UdfpsFpDrawable = UdfpsFpDrawable(context)

    override fun getDrawable(): UdfpsDrawable = fingerprintDrawable
}

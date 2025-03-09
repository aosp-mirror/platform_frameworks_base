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
package com.android.test.silkfx.materials

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

class BlurBehindContainer(context: Context, attributeSet: AttributeSet) : FrameLayout(context, attributeSet) {
    override fun onFinishInflate() {
        super.onFinishInflate()
        setBackdropRenderEffect(
            RenderEffect.createBlurEffect(16.0f, 16.0f, Shader.TileMode.CLAMP))
    }
}
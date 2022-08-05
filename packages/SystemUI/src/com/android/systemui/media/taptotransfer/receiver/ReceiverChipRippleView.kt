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

package com.android.systemui.media.taptotransfer.receiver

import android.content.Context
import android.util.AttributeSet
import com.android.systemui.ripple.RippleView

/**
 * An expanding ripple effect for the media tap-to-transfer receiver chip.
 */
class ReceiverChipRippleView(context: Context?, attrs: AttributeSet?) : RippleView(context, attrs) {
    init {
        // TODO: use RippleShape#ELLIPSE when calling setupShader.
        setupShader()
        setRippleFill(true)
        duration = 3000L
    }
}

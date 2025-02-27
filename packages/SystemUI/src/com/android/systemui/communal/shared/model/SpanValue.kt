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

package com.android.systemui.communal.shared.model

/** Models possible span values for different grid formats. */
sealed interface SpanValue {
    val value: Int

    @Deprecated("Use Responsive sizes instead")
    @JvmInline
    value class Fixed(override val value: Int) : SpanValue

    @JvmInline value class Responsive(override val value: Int) : SpanValue
}

fun SpanValue.toResponsive(): SpanValue.Responsive =
    when (this) {
        is SpanValue.Responsive -> this
        is SpanValue.Fixed -> SpanValue.Responsive((this.value / 3).coerceAtMost(1))
    }

fun SpanValue.toFixed(): SpanValue.Fixed =
    when (this) {
        is SpanValue.Fixed -> this
        is SpanValue.Responsive -> SpanValue.Fixed((this.value * 3).coerceIn(3, 6))
    }

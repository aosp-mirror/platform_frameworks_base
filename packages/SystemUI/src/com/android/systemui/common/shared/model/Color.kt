/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.common.shared.model

import android.annotation.AttrRes
import android.annotation.ColorInt
import android.annotation.ColorRes

/**
 * Models a color that can be either a specific [Color.Loaded] value or a resolvable theme
 * [Color.Attribute]
 */
sealed interface Color {

    data class Loaded(@ColorInt val color: Int) : Color

    data class Attribute(@AttrRes val attribute: Int) : Color

    data class Resource(@ColorRes val colorRes: Int) : Color
}

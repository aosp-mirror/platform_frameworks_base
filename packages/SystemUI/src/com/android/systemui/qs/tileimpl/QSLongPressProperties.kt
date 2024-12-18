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

package com.android.systemui.qs.tileimpl

/**
 * List of properties that define the state of a tile during a long-press gesture.
 *
 * These properties are used during animation if a tile supports a long-press action.
 */
data class QSLongPressProperties(
    var height: Float,
    var width: Float,
    var cornerRadius: Float,
    var backgroundColor: Int,
    var labelColor: Int,
    var secondaryLabelColor: Int,
    var chevronColor: Int,
    var overlayColor: Int,
    var iconColor: Int,
)

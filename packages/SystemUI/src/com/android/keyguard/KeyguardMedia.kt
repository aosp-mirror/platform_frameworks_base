/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.graphics.drawable.Drawable

import java.util.List

/** State for lock screen media controls. */
data class KeyguardMedia(
    val foregroundColor: Int,
    val backgroundColor: Int,
    val app: String?,
    val appIcon: Drawable?,
    val artist: String?,
    val song: String?,
    val artwork: Drawable?,
    val actionIcons: List<Drawable>
)

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

package com.android.systemui.statusbar.notification.stack.shared.model

/** Models the corner rounds of the notification stack. */
data class ShadeScrimRounding(
    /** Whether the top corners of the notification stack should be rounded. */
    val isTopRounded: Boolean = false,
    /** Whether the bottom corners of the notification stack should be rounded. */
    val isBottomRounded: Boolean = false,
)

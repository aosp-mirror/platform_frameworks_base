/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade.transition

/** An interpolator interface for the shade expansion. */
interface LargeScreenShadeInterpolator {

    /** Returns the alpha for the behind/back scrim. */
    fun getBehindScrimAlpha(fraction: Float): Float

    /** Returns the alpha for the notification scrim. */
    fun getNotificationScrimAlpha(fraction: Float): Float

    /** Returns the alpha for the notifications. */
    fun getNotificationContentAlpha(fraction: Float): Float

    /** Returns the alpha for the notifications footer (Manager, Clear All). */
    fun getNotificationFooterAlpha(fraction: Float): Float

    /** Returns the alpha for the QS panel. */
    fun getQsAlpha(fraction: Float): Float
}

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

data class ShadeScrollState(
    /**
     * Whether the notification stack is scrolled to the top (i.e. when freshly opened). It also
     * returns true, when scrolling is not possible because all the content fits in the current
     * viewport.
     */
    val isScrolledToTop: Boolean = true,

    /**
     * Current scroll position of the shade. 0 when scrolled to the top, [maxScrollPosition] when
     * scrolled all the way to the bottom.
     */
    val scrollPosition: Int = 0,

    /**
     * Max scroll position of the shade. 0, when no scrolling is possible e.g. all the content fits
     * in the current viewport.
     */
    val maxScrollPosition: Int = 0,
)

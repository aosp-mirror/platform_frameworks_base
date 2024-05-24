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

package com.android.systemui.volume.panel.ui.layout

import com.android.systemui.volume.panel.ui.viewmodel.ComponentState

/** Represents components grouping into the layout. */
data class ComponentsLayout(
    /** Top section of the Volume Panel. It's typically shown above the [contentComponents]. */
    val headerComponents: List<ComponentState>,
    /** Main Volume Panel content. */
    val contentComponents: List<ComponentState>,
    /** Bottom section of the Volume Panel. It's typically shown below the [contentComponents]. */
    val footerComponents: List<ComponentState>,
    /** This is a separated entity that is always visible on the bottom of the Volume Panel. */
    val bottomBarComponent: ComponentState,
)

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

package com.android.systemui.controls.controller

import android.content.ComponentName

/**
 * Stores basic information about a Structure to persist and keep track of favorites.
 *
 * Every [component] [structure] pair uniquely identifies the structure.
 *
 * @property componentName the name of the component that provides the [Control].
 * @property structure common structure name of all underlying [controls], or empty string
 * @property controls all controls in the name structure
 */
data class StructureInfo(
    val componentName: ComponentName,
    val structure: CharSequence,
    val controls: List<ControlInfo>
)

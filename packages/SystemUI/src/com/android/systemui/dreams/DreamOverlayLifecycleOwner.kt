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

package com.android.systemui.dreams

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import javax.inject.Inject

/**
 * {@link DreamOverlayLifecycleOwner} is a concrete implementation of {@link LifecycleOwner}, which
 * provides access to an associated {@link LifecycleRegistry}.
 */
class DreamOverlayLifecycleOwner @Inject constructor() : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() {
            return registry
        }
}

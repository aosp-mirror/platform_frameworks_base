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

package com.android.compose.animation

import android.view.View
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner as AndroidXViewTreeSavedStateRegistryOwner

// TODO(b/262222023): Remove this workaround and import the new savedstate libraries in tm-qpr-dev
// instead.
object ViewTreeSavedStateRegistryOwner {
    fun set(view: View, owner: SavedStateRegistryOwner?) {
        AndroidXViewTreeSavedStateRegistryOwner.set(view, owner)
    }

    fun get(view: View): SavedStateRegistryOwner? {
        return AndroidXViewTreeSavedStateRegistryOwner.get(view)
    }
}

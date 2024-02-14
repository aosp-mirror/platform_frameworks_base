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

package com.android.systemui.scene.shared.model

interface UserActionDistance {

    /**
     * Return the **absolute** distance of the user action (in pixels) given the size of the scene
     * we are animating from and the orientation.
     */
    fun absoluteDistance(fromSceneWidth: Int, fromSceneHeight: Int, isHorizontal: Boolean): Float
}

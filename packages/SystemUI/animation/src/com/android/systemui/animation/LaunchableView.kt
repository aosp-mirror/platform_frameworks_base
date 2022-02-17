/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.animation

/** A view that can expand/launch into an app or a dialog. */
interface LaunchableView {
    /**
     * Set whether this view should block/prevent all visibility changes. This ensures that this
     * view remains invisible during the launch animation given that it is ghosted and already drawn
     * somewhere else.
     *
     * Note that when this is set to true, both the [normal][android.view.View.setVisibility] and
     * [transition][android.view.View.setTransitionVisibility] visibility changes must be blocked.
     */
    fun setShouldBlockVisibilityChanges(block: Boolean)
}
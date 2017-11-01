/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view;

/**
 * Listener to be invoked when wallpaper visibility changes.
 * {@hide}
 */
oneway interface IWallpaperVisibilityListener {
    /**
     * Method that will be invoked when wallpaper becomes visible or hidden.
     * @param visible True if wallpaper is being displayed; false otherwise.
     * @param displayId The id of the display where wallpaper visibility changed.
     */
    void onWallpaperVisibilityChanged(boolean visible, int displayId);
}

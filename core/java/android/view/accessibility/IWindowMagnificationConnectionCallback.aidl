/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.accessibility;

import android.graphics.Rect;

/**
 * interface to notify the change of the window magnifier bounds and request to change
 * the magnification mode.
 *
 * @hide
 */
 oneway interface IWindowMagnificationConnectionCallback {

    /**
     * Called when the bounds of the window magnifier is changed.
     *
     * @param displayId The logical display id.
     * @param bounds The window magnifier bounds in screen coordinates.
     */
    void onWindowMagnifierBoundsChanged(int display, in Rect bounds);
    /**
     * Changes the magnification mode on specified display. It is invoked by System UI when the
     *  switch button is toggled.
     *
     * @param displayId The logical display id.
     * @param magnificationMode new magnification mode.
     */
    void onChangeMagnificationMode(int display, int magnificationMode);
}

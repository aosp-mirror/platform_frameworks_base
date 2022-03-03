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
 * interface to notify the changes of the window magnification and request to change
 * the magnification mode.
 *
 * @hide
 */
 oneway interface IWindowMagnificationConnectionCallback {

    /**
     * Called when the bounds of the mirrow window is changed.
     *
     * @param displayId The logical display id.
     * @param bounds The window magnifier bounds in screen coordinates.
     */
    void onWindowMagnifierBoundsChanged(int displayId, in Rect bounds);

    /**
     * Changes the magnification mode on specified display. It is invoked by System UI when the
     *  switch button is toggled.
     *
     * @param displayId The logical display id.
     * @param magnificationMode new magnification mode.
     */
    void onChangeMagnificationMode(int displayId, int magnificationMode);

    /**
     * Called when the magnified bounds is changed.
     *
     * @param displayId The logical display id.
     * @param sourceBounds The magnified bounds in screen coordinates.
     */
    void onSourceBoundsChanged(int displayId, in Rect sourceBounds);

    /**
     * Called when the accessibility action of scale requests to be performed.
     * It is invoked from System UI. And the action is provided by the mirror window.
     *
     * @param displayId The logical display id.
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     */
    void onPerformScaleAction(int displayId, float scale);

    /**
     * Called when the accessibility action is performed.
     *
     * @param displayId The logical display id.
     */
    void onAccessibilityActionPerformed(int displayId);

    /**
     * Called when the user is performing move action.
     *
     * @param displayId The logical display id.
     */
    void onMove(int displayId);

}

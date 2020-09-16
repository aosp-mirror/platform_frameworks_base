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

import android.graphics.PointF;
import android.graphics.Rect;
import android.view.accessibility.IWindowMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

/**
 * Interface for interaction between {@link AccessibilityManagerService}
 * and {@link WindowMagnification} in SystemUI.
 *
 * @hide
 */
oneway interface IWindowMagnificationConnection {

    /**
     * Enables window magnification on specified display with given center and scale and animation.
     *
     * @param displayId The logical display id.
     * @param scale magnification scale.
     * @param centerX the screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY the screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param callback The callback called when the animation is completed or interrupted.
     */
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
        in IRemoteMagnificationAnimationCallback callback);

    /**
     * Sets the scale of the window magnifier on specified display.
     *
     * @param displayId The logical display id.
     * @param scale magnification scale.
     */
    void setScale(int displayId, float scale);

     /**
     * Disables window magnification on specified display with animation.
     *
     * @param displayId The logical display id.
     * @param callback The callback called when the animation is completed or interrupted.
     */
    void disableWindowMagnification(int displayId,
        in IRemoteMagnificationAnimationCallback callback);

    /**
     * Moves the window magnifier on the specified display. It has no effect while animating.
     *
     * @param offsetX the amount in pixels to offset the window magnifier in the X direction, in
     *                current screen pixels.
     * @param offsetY the amount in pixels to offset the window magnifier in the Y direction, in
     *                current screen pixels.
     */
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY);

    /**
     * Requests System UI show magnification mode button UI on the specified display.
     *
     * @param displayId The logical display id.
     * @param magnificationMode the current magnification mode.
     */
    void showMagnificationButton(int displayId, int magnificationMode);

    /**
     * Requests System UI remove magnification mode button UI on the specified display.
     *
     * @param displayId The logical display id.
     */
    void removeMagnificationButton(int displayId);

    /**
     * Sets {@link IWindowMagnificationConnectionCallback} to receive the request or the callback.
     *
     * @param callback the interface to be called.
     */
    void setConnectionCallback(in IWindowMagnificationConnectionCallback callback);
}

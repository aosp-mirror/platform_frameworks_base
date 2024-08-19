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

package com.android.systemui.accessibility;

import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;

import android.graphics.Rect;

/**
 * A callback to inform {@link com.android.server.accessibility.AccessibilityManagerService} about
 * the UI state change or the user interaction.
 */
interface WindowMagnifierCallback {
    /**
     * Called when the bounds of window magnifier is changed.
     * @param displayId The logical display id.
     * @param bounds The bounds of window magnifier on the screen.
     */
    void onWindowMagnifierBoundsChanged(int displayId, Rect bounds);

    /**
     * Called when the magnified bounds is changed.
     *
     * @param displayId The logical display id.
     * @param sourceBounds The magnified bounds in screen coordinates.
     */
    void onSourceBoundsChanged(int displayId, Rect sourceBounds);

    /**
     * Called when the accessibility action of scale requests to be performed.
     * It is invoked from System UI. And the action is provided by the mirror window.
     *
     * @param displayId The logical display id.
     * @param scale the target scale, or {@link Float#NaN} to leave unchanged
     * @param updatePersistence whether the scale should be persisted
     */
    void onPerformScaleAction(int displayId, float scale, boolean updatePersistence);

    /**
     * Called when the accessibility action is performed.
     *
     * @param displayId The logical display id.
     */
    void onAccessibilityActionPerformed(int displayId);

    /**
     * Called when the user is performing a move action.
     *
     * @param displayId The logical display id.
     */
    void onMove(int displayId);

    /**
     * Called when magnification settings button clicked.
     *
     * @param displayId The logical display id.
     */
    void onClickSettingsButton(int displayId);

    /**
     * Called when restoring the magnification window size.
     */
    void onWindowMagnifierBoundsRestored(int displayId, @MagnificationSize int index);
}

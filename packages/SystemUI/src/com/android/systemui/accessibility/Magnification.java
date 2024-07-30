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

package com.android.systemui.accessibility;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

import com.android.systemui.CoreStartable;

/** Interface for managing magnification connection calls from system process. */
public interface Magnification extends CoreStartable {

    @MainThread
    void enableWindowMagnification(
            int displayId,
            float scale,
            float centerX,
            float centerY,
            float magnificationFrameOffsetRatioX,
            float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback callback);

    @MainThread
    void setScaleForWindowMagnification(int displayId, float scale);

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY);

    @MainThread
    void disableWindowMagnification(
            int displayId, @Nullable IRemoteMagnificationAnimationCallback callback);

    @MainThread
    void onFullscreenMagnificationActivationChanged(int displayId, boolean activated);

    @MainThread
    void showMagnificationButton(int displayId, int magnificationMode);

    @MainThread
    void removeMagnificationButton(int displayId);

    @MainThread
    void setUserMagnificationScale(int userId, int displayId, float scale);

    @MainThread
    void hideMagnificationSettingsPanel(int displayId);

    @MainThread
    void moveWindowMagnifierToPosition(
            int displayId,
            float positionX,
            float positionY,
            IRemoteMagnificationAnimationCallback callback);
}

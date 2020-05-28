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

package com.android.server.accessibility.magnification;

import com.android.server.accessibility.BaseEventStreamTransformation;

/**
 * A base class that detects gestures and defines common methods for magnification.
 */
public abstract class MagnificationGestureHandler extends BaseEventStreamTransformation {

    protected final MagnificationGestureHandler.ScaleChangedListener mListener;

    protected MagnificationGestureHandler(ScaleChangedListener listener) {
        mListener = listener;
    }

    /**
     * Interface for listening to the magnification scaling gesture.
     */
    public interface ScaleChangedListener {
        /**
         * Called when the magnification scale is changed by users.
         *
         * @param displayId The logical display id
         * @param mode  The magnification mode
         */
        void onMagnificationScaleChanged(int displayId, int mode);
    }

    /**
     * Called when the shortcut target is magnification.
     */
    public abstract void notifyShortcutTriggered();

    /**
     * Indicates the magnification mode.
     *
     * @return the magnification mode of the handler
     *
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     * @see android.provider.Settings.Secure#ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     */
    public abstract int getMode();
}

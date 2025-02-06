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

package com.android.server.wallpaper;

/**
 * Wallpaper manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class WallpaperManagerInternal {

    /**
     * Notifies the display is ready for adding wallpaper on it.
     */
    public abstract void onDisplayAddSystemDecorations(int displayId);

    /** Notifies when display stop showing system decorations and wallpaper. */
    public abstract void onDisplayRemoveSystemDecorations(int displayId);

    /** Notifies when the screen finished turning on and is visible to the user. */
    public abstract void onScreenTurnedOn(int displayId);

    /** Notifies when the screen starts turning on and is not yet visible to the user. */
    public abstract void onScreenTurningOn(int displayId);

    /** Notifies when the keyguard is going away. Sent right after the bouncer is gone. */
    public abstract void onKeyguardGoingAway();
}

/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.twilight;

import android.annotation.NonNull;
import android.os.Handler;

/**
 * This class provides sunrise/sunset information based on the device's current location.
 */
public interface TwilightManager {
    /**
     * Register a listener to be notified whenever the twilight state changes.
     *
     * @param listener the {@link TwilightListener} to be notified
     * @param handler the {@link Handler} to use to notify the listener
     */
    void registerListener(@NonNull TwilightListener listener, @NonNull Handler handler);

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener the {@link TwilightListener} to be unregistered
     */
    void unregisterListener(@NonNull TwilightListener listener);

    /**
     * Returns the last {@link TwilightState}, or {@code null} if not available.
     */
    TwilightState getLastTwilightState();
}

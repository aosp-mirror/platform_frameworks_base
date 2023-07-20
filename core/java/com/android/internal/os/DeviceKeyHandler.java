/*
 * Copyright (C) 2012 The CyanogenMod Project
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

package com.android.internal.os;

import android.view.KeyEvent;

public interface DeviceKeyHandler {

    /**
     * Invoked when an unknown key was detected by the system, letting the device handle
     * this special keys prior to pass the key to the active app.
     *
     * @param event The key event to be handled
     * @return null if event is consumed, KeyEvent to be handled otherwise
     */
    public KeyEvent handleKeyEvent(KeyEvent event);
}

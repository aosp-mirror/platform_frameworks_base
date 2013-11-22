/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.view.DisplayInfo;
import android.view.Surface;

/** @hide */
interface IDisplayManager {
    DisplayInfo getDisplayInfo(int displayId);
    int[] getDisplayIds();

    void registerCallback(in IDisplayManagerCallback callback);

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    // The process must have previously registered a callback.
    void startWifiDisplayScan();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void stopWifiDisplayScan();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void connectWifiDisplay(String address);

    // No permissions required.
    void disconnectWifiDisplay();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void renameWifiDisplay(String address, String alias);

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void forgetWifiDisplay(String address);

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void pauseWifiDisplay();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    void resumeWifiDisplay();

    // No permissions required.
    WifiDisplayStatus getWifiDisplayStatus();

    // Requires CAPTURE_VIDEO_OUTPUT or CAPTURE_SECURE_VIDEO_OUTPUT for certain
    // combinations of flags.
    int createVirtualDisplay(IBinder token, String packageName,
            String name, int width, int height, int densityDpi, in Surface surface, int flags);

    // No permissions required but must be same Uid as the creator.
    void releaseVirtualDisplay(in IBinder token);
}

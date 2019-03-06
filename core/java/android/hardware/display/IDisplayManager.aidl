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

import android.content.pm.ParceledListSlice;
import android.graphics.Point;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.Curve;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.media.projection.IMediaProjection;
import android.view.DisplayInfo;
import android.view.Surface;

/** @hide */
interface IDisplayManager {
    @UnsupportedAppUsage
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

    // Requires CONFIGURE_DISPLAY_COLOR_MODE
    void requestColorMode(int displayId, int colorMode);

    // Requires CONTROL_DISPLAY_SATURATION
    void setSaturationLevel(float level);

    // Requires CAPTURE_VIDEO_OUTPUT, CAPTURE_SECURE_VIDEO_OUTPUT, or an appropriate
    // MediaProjection token for certain combinations of flags.
    int createVirtualDisplay(in IVirtualDisplayCallback callback,
            in IMediaProjection projectionToken, String packageName, String name,
            int width, int height, int densityDpi, in Surface surface, int flags, String uniqueId);

    // No permissions required, but must be same Uid as the creator.
    void resizeVirtualDisplay(in IVirtualDisplayCallback token,
            int width, int height, int densityDpi);

    // No permissions required but must be same Uid as the creator.
    void setVirtualDisplaySurface(in IVirtualDisplayCallback token, in Surface surface);

    // No permissions required but must be same Uid as the creator.
    void releaseVirtualDisplay(in IVirtualDisplayCallback token);

    // Get a stable metric for the device's display size. No permissions required.
    Point getStableDisplaySize();

    // Requires BRIGHTNESS_SLIDER_USAGE permission.
    ParceledListSlice getBrightnessEvents(String callingPackage);

    // Requires ACCESS_AMBIENT_LIGHT_STATS permission.
    ParceledListSlice getAmbientBrightnessStats();

    // Sets the global brightness configuration for a given user. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS, and INTERACT_ACROSS_USER if the user being configured is not
    // the same as the calling user.
    void setBrightnessConfigurationForUser(in BrightnessConfiguration c, int userId,
            String packageName);

    // Gets the global brightness configuration for a given user. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS, and INTERACT_ACROSS_USER if the user is not
    // the same as the calling user.
    BrightnessConfiguration getBrightnessConfigurationForUser(int userId);

    // Gets the default brightness configuration if configured.
    BrightnessConfiguration getDefaultBrightnessConfiguration();

    // Temporarily sets the display brightness.
    void setTemporaryBrightness(int brightness);

    // Temporarily sets the auto brightness adjustment factor.
    void setTemporaryAutoBrightnessAdjustment(float adjustment);

    // Get the minimum brightness curve.
    Curve getMinimumBrightnessCurve();
}

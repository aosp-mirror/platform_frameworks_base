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
import android.hardware.OverlayProperties;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.Curve;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.hardware.display.HdrConversionMode;
import android.hardware.display.IDisplayManagerCallback;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.media.projection.IMediaProjection;
import android.view.Display.Mode;
import android.view.DisplayInfo;
import android.view.Surface;

/** @hide */
interface IDisplayManager {
    @UnsupportedAppUsage
    DisplayInfo getDisplayInfo(int displayId);
    int[] getDisplayIds(boolean includeDisabled);

    boolean isUidPresentOnDisplay(int uid, int displayId);

    void registerCallback(in IDisplayManagerCallback callback);
    void registerCallbackWithEventMask(in IDisplayManagerCallback callback, long eventsMask);

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    // The process must have previously registered a callback.
    @EnforcePermission("CONFIGURE_WIFI_DISPLAY")
    void startWifiDisplayScan();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    @EnforcePermission("CONFIGURE_WIFI_DISPLAY")
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
    @EnforcePermission("CONFIGURE_WIFI_DISPLAY")
    void pauseWifiDisplay();

    // Requires CONFIGURE_WIFI_DISPLAY permission.
    @EnforcePermission("CONFIGURE_WIFI_DISPLAY")
    void resumeWifiDisplay();

    // No permissions required.
    WifiDisplayStatus getWifiDisplayStatus();

    // Requires WRITE_SECURE_SETTINGS permission.
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void setUserDisabledHdrTypes(in int[] userDisabledTypes);

    // Requires WRITE_SECURE_SETTINGS permission.
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed);

    // No permissions required.
    boolean areUserDisabledHdrTypesAllowed();

    // No permissions required.
    int[] getUserDisabledHdrTypes();

    // Requires ACCESS_SURFACE_FLINGER permission.
    void overrideHdrTypes(int displayId, in int[] modes);

    // Requires CONFIGURE_DISPLAY_COLOR_MODE
    @EnforcePermission("CONFIGURE_DISPLAY_COLOR_MODE")
    void requestColorMode(int displayId, int colorMode);

    // Requires CAPTURE_VIDEO_OUTPUT, CAPTURE_SECURE_VIDEO_OUTPUT, or an appropriate
    // MediaProjection token for certain combinations of flags.
    int createVirtualDisplay(in VirtualDisplayConfig virtualDisplayConfig,
            in IVirtualDisplayCallback callback, in IMediaProjection projectionToken,
            String packageName);

    // No permissions required, but must be same Uid as the creator.
    void resizeVirtualDisplay(in IVirtualDisplayCallback token,
            int width, int height, int densityDpi);

    // No permissions required but must be same Uid as the creator.
    void setVirtualDisplaySurface(in IVirtualDisplayCallback token, in Surface surface);

    // No permissions required but must be same Uid as the creator.
    void releaseVirtualDisplay(in IVirtualDisplayCallback token);

    // No permissions required but must be same Uid as the creator.
    void setVirtualDisplayRotation(in IVirtualDisplayCallback token, int rotation);

    // Get a stable metric for the device's display size. No permissions required.
    Point getStableDisplaySize();

    // Requires BRIGHTNESS_SLIDER_USAGE permission.
    @EnforcePermission("BRIGHTNESS_SLIDER_USAGE")
    ParceledListSlice getBrightnessEvents(String callingPackage);

    // Requires ACCESS_AMBIENT_LIGHT_STATS permission.
    @EnforcePermission("ACCESS_AMBIENT_LIGHT_STATS")
    ParceledListSlice getAmbientBrightnessStats();

    // Sets the global brightness configuration for a given user. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS, and INTERACT_ACROSS_USER if the user being configured is not
    // the same as the calling user.
    @EnforcePermission("CONFIGURE_DISPLAY_BRIGHTNESS")
    void setBrightnessConfigurationForUser(in BrightnessConfiguration c, int userId,
            String packageName);

    // Sets the global brightness configuration for a given display. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS.
    @EnforcePermission("CONFIGURE_DISPLAY_BRIGHTNESS")
    void setBrightnessConfigurationForDisplay(in BrightnessConfiguration c, String uniqueDisplayId,
            int userId, String packageName);

    // Gets the brightness configuration for a given display. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS.
    @EnforcePermission("CONFIGURE_DISPLAY_BRIGHTNESS")
    BrightnessConfiguration getBrightnessConfigurationForDisplay(String uniqueDisplayId,
            int userId);

    // Gets the global brightness configuration for a given user. Requires
    // CONFIGURE_DISPLAY_BRIGHTNESS, and INTERACT_ACROSS_USER if the user is not
    // the same as the calling user.
    BrightnessConfiguration getBrightnessConfigurationForUser(int userId);

    // Gets the default brightness configuration if configured.
    @EnforcePermission("CONFIGURE_DISPLAY_BRIGHTNESS")
    BrightnessConfiguration getDefaultBrightnessConfiguration();

    // Gets the last requested minimal post processing settings for display with displayId.
    boolean isMinimalPostProcessingRequested(int displayId);

    // Temporarily sets the display brightness.
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    void setTemporaryBrightness(int displayId, float brightness);

    // Saves the display brightness.
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    void setBrightness(int displayId, float brightness);

    // Retrieves the display brightness.
    float getBrightness(int displayId);

    // Temporarily sets the auto brightness adjustment factor.
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    void setTemporaryAutoBrightnessAdjustment(float adjustment);

    // Get the minimum brightness curve.
    Curve getMinimumBrightnessCurve();

    // Get Brightness Information for the specified display.
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    BrightnessInfo getBrightnessInfo(int displayId);

    // Gets the id of the preferred wide gamut color space for all displays.
    // The wide gamut color space is returned from composition pipeline
    // based on hardware capability.
    int getPreferredWideGamutColorSpaceId();

    // Sets the user preferred display mode.
    // Requires MODIFY_USER_PREFERRED_DISPLAY_MODE permission.
    @EnforcePermission("MODIFY_USER_PREFERRED_DISPLAY_MODE")
    void setUserPreferredDisplayMode(int displayId, in Mode mode);
    Mode getUserPreferredDisplayMode(int displayId);
    Mode getSystemPreferredDisplayMode(int displayId);

    // Sets the HDR conversion mode for a device.
    // Requires MODIFY_HDR_CONVERSION_MODE permission.
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
                + ".permission.MODIFY_HDR_CONVERSION_MODE)")
    void setHdrConversionMode(in HdrConversionMode hdrConversionMode);
    HdrConversionMode getHdrConversionModeSetting();
    HdrConversionMode getHdrConversionMode();
    int[] getSupportedHdrOutputTypes();

    // When enabled the app requested display resolution and refresh rate is always selected
    // in DisplayModeDirector regardless of user settings and policies for low brightness, low
    // battery etc.
    @EnforcePermission("OVERRIDE_DISPLAY_MODE_REQUESTS")
    void setShouldAlwaysRespectAppRequestedMode(boolean enabled);
    @EnforcePermission("OVERRIDE_DISPLAY_MODE_REQUESTS")
    boolean shouldAlwaysRespectAppRequestedMode();

    // Sets the refresh rate switching type.
    @EnforcePermission("MODIFY_REFRESH_RATE_SWITCHING_TYPE")
    void setRefreshRateSwitchingType(int newValue);

    // Returns the refresh rate switching type.
    int getRefreshRateSwitchingType();

    // Query for DISPLAY_DECORATION support.
    DisplayDecorationSupport getDisplayDecorationSupport(int displayId);

    // This method is to support behavior that was calling hidden APIs. The caller was requesting
    // to set the layerStack after the display was created, which is not something we support in
    // DMS. This should be deleted in V release.
    void setDisplayIdToMirror(in IBinder token, int displayId);

    // Query overlay properties of the device
    OverlayProperties getOverlaySupport();

    // Enable a connected display that is disabled.
    @EnforcePermission("MANAGE_DISPLAYS")
    void enableConnectedDisplay(int displayId);

    // Disable a connected display that is enabled.
    @EnforcePermission("MANAGE_DISPLAYS")
    void disableConnectedDisplay(int displayId);

    // Request to power display OFF or reset it to a power state it supposed to have.
    @EnforcePermission("MANAGE_DISPLAYS")
    boolean requestDisplayPower(int displayId, int state);

    // Restricts display modes to specified modeIds.
    @EnforcePermission("RESTRICT_DISPLAY_MODES")
    void requestDisplayModes(in IBinder token, int displayId, in @nullable int[] modeIds);

    // Get the highest defined HDR/SDR ratio for a display.
    float getHighestHdrSdrRatio(int displayId);

    // Get the mapping between the doze brightness sensor values and brightness values
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    float[] getDozeBrightnessSensorValueToBrightness(int displayId);

    // Get the default doze brightness
    @EnforcePermission("CONTROL_DISPLAY_BRIGHTNESS")
    float getDefaultDozeBrightness(int displayId);
}

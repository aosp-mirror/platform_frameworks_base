/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.Nullable;
import android.graphics.Point;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

/**
 * Display manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class DisplayManagerInternal {
    /**
     * Called by the power manager to initialize power management facilities.
     */
    public abstract void initPowerManagement(DisplayPowerCallbacks callbacks,
            Handler handler, SensorManager sensorManager);

    /**
     * Called by the power manager to request a new power state.
     * <p>
     * The display power controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     * </p>
     *
     * @param request The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     * negative proximity before turning the screen back on, assuming the screen
     * was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged()}
     * then try the request again later until the state converges.
     */
    public abstract boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity);

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    public abstract boolean isProximitySensorAvailable();

    /**
     * Screenshot for internal system-only use such as rotation, etc.  This method includes
     * secure layers and the result should never be exposed to non-system applications.
     * This method does not apply any rotation and provides the output in natural orientation.
     *
     * @param displayId The display id to take the screenshot of.
     * @return The buffer or null if we have failed.
     */
    public abstract SurfaceControl.ScreenshotGraphicBuffer systemScreenshot(int displayId);

    /**
     * General screenshot functionality that excludes secure layers and applies appropriate
     * rotation that the device is currently in.
     *
     * @param displayId The display id to take the screenshot of.
     * @return The buffer or null if we have failed.
     */
    public abstract SurfaceControl.ScreenshotGraphicBuffer userScreenshot(int displayId);

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @return The logical display info, or null if the display does not exist.  The
     * returned object must be treated as immutable.
     */
    public abstract DisplayInfo getDisplayInfo(int displayId);

    /**
     * Returns the position of the display's projection.
     *
     * @param displayId The logical display id.
     * @return The x, y coordinates of the display, or null if the display does not exist. The
     * return object must be treated as immutable.
     */
    @Nullable
    public abstract Point getDisplayPosition(int displayId);

    /**
     * Registers a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to register.
     */
    public abstract void registerDisplayTransactionListener(DisplayTransactionListener listener);

    /**
     * Unregisters a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to unregister.
     */
    public abstract void unregisterDisplayTransactionListener(DisplayTransactionListener listener);

    /**
     * Overrides the display information of a particular logical display.
     * This is used by the window manager to control the size and characteristics
     * of the default display.  It is expected to apply the requested change
     * to the display information synchronously so that applications will immediately
     * observe the new state.
     *
     * NOTE: This method must be the only entry point by which the window manager
     * influences the logical configuration of displays.
     *
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public abstract void setDisplayInfoOverrideFromWindowManager(
            int displayId, DisplayInfo info);

    /**
     * Get current display info without override from WindowManager.
     * Current implementation of LogicalDisplay#getDisplayInfoLocked() always returns display info
     * with overrides from WM if set. This method can be used for getting real display size without
     * overrides to determine if real changes to display metrics happened.
     * @param displayId Id of the target display.
     * @param outInfo {@link DisplayInfo} to fill.
     */
    public abstract void getNonOverrideDisplayInfo(int displayId, DisplayInfo outInfo);

    /**
     * Called by the window manager to perform traversals while holding a
     * surface flinger transaction.
     */
    public abstract void performTraversal(Transaction t);

    /**
     * Tells the display manager about properties of the display that depend on the windows on it.
     * This includes whether there is interesting unique content on the specified logical display,
     * and whether the one of the windows has a preferred refresh rate.
     * <p>
     * If the display has unique content, then the display manager arranges for it
     * to be presented on a physical display if appropriate.  Otherwise, the display manager
     * may choose to make the physical display mirror some other logical display.
     * </p>
     *
     * <p>
     * If one of the windows on the display has a preferred refresh rate that's supported by the
     * display, then the display manager will request its use.
     * </p>
     *
     * @param displayId The logical display id to update.
     * @param hasContent True if the logical display has content. This is used to control automatic
     * mirroring.
     * @param requestedRefreshRate The preferred refresh rate for the top-most visible window that
     * has a preference.
     * @param requestedModeId The preferred mode id for the top-most visible window that has a
     * preference.
     * @param requestedMinimalPostProcessing The preferred minimal post processing setting for the
     * display. This is true when there is at least one visible window that wants minimal post
     * processng on.
     * @param inTraversal True if called from WindowManagerService during a window traversal
     * prior to call to performTraversalInTransactionFromWindowManager.
     */
    public abstract void setDisplayProperties(int displayId, boolean hasContent,
            float requestedRefreshRate, int requestedModeId, boolean requestedMinimalPostProcessing,
            boolean inTraversal);

    /**
     * Applies an offset to the contents of a display, for example to avoid burn-in.
     * <p>
     * TODO: Technically this should be associated with a physical rather than logical
     * display but this is good enough for now.
     * </p>
     *
     * @param displayId The logical display id to update.
     * @param x The X offset by which to shift the contents of the display.
     * @param y The Y offset by which to shift the contents of the display.
     */
    public abstract void setDisplayOffsets(int displayId, int x, int y);

    /**
     * Disables scaling for a display.
     *
     * @param displayId The logical display id to disable scaling for.
     * @param disableScaling {@code true} to disable scaling,
     * {@code false} to use the default scaling behavior of the logical display.
     */
    public abstract void setDisplayScalingDisabled(int displayId, boolean disableScaling);

    /**
     * Provide a list of UIDs that are present on the display and are allowed to access it.
     *
     * @param displayAccessUIDs Mapping displayId -> int array of UIDs.
     */
    public abstract void setDisplayAccessUIDs(SparseArray<IntArray> displayAccessUIDs);

    /**
     * Persist brightness slider events and ambient brightness stats.
     */
    public abstract void persistBrightnessTrackerState();

    /**
     * Notifies the display manager that resource overlays have changed.
     */
    public abstract void onOverlayChanged();

    /**
     * Get the attributes available for display color sampling.
     * @param displayId id of the display to collect the sample from.
     *
     * @return The attributes the display supports, or null if sampling is not supported.
     */
    @Nullable
    public abstract DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributes(
            int displayId);

    /**
     * Enable or disable the collection of color samples.
     *
     * @param displayId id of the display to collect the sample from.
     * @param componentMask a bitmask of the color channels to collect samples for, or zero for all
     *                      available.
     * @param maxFrames maintain a ringbuffer of the last maxFrames.
     * @param enable True to enable, False to disable.
     *
     * @return True if sampling was enabled, false if failure.
     */
    public abstract boolean setDisplayedContentSamplingEnabled(
            int displayId, boolean enable, int componentMask, int maxFrames);

    /**
     * Accesses the color histogram statistics of displayed frames on devices that support sampling.
     *
     * @param displayId id of the display to collect the sample from
     * @param maxFrames limit the statistics to the last maxFrames number of frames.
     * @param timestamp discard statistics that were collected prior to timestamp, where timestamp
     *                  is given as CLOCK_MONOTONIC.
     * @return The statistics representing a histogram of the color distribution of the frames
     *         displayed on-screen, or null if sampling is not supported.
    */
    @Nullable
    public abstract DisplayedContentSample getDisplayedContentSample(
            int displayId, long maxFrames, long timestamp);

    /**
     * Temporarily ignore proximity-sensor-based display behavior until there is a change
     * to the proximity sensor state. This allows the display to turn back on even if something
     * is obstructing the proximity sensor.
     */
    public abstract void ignoreProximitySensorUntilChanged();

    /**
     * Describes the requested power state of the display.
     *
     * This object is intended to describe the general characteristics of the
     * power state, such as whether the screen should be on or off and the current
     * brightness controls leaving the DisplayPowerController to manage the
     * details of how the transitions between states should occur.  The goal is for
     * the PowerManagerService to focus on the global power state and not
     * have to micro-manage screen off animations, auto-brightness and other effects.
     */
    public static final class DisplayPowerRequest {
        // Policy: Turn screen off as if the user pressed the power button
        // including playing a screen off animation if applicable.
        public static final int POLICY_OFF = 0;
        // Policy: Enable dozing and always-on display functionality.
        public static final int POLICY_DOZE = 1;
        // Policy: Make the screen dim when the user activity timeout is
        // about to expire.
        public static final int POLICY_DIM = 2;
        // Policy: Make the screen bright as usual.
        public static final int POLICY_BRIGHT = 3;
        // Policy: Keep the screen and display optimized for VR mode.
        public static final int POLICY_VR = 4;

        // The basic overall policy to apply: off, doze, dim or bright.
        public int policy;

        // If true, the proximity sensor overrides the screen state when an object is
        // nearby, turning it off temporarily until the object is moved away.
        public boolean useProximitySensor;

        // An override of the screen brightness.
        // Set to PowerManager.BRIGHTNESS_INVALID if there's no override.
        public float screenBrightnessOverride;

        // An override of the screen auto-brightness adjustment factor in the range -1 (dimmer) to
        // 1 (brighter). Set to Float.NaN if there's no override.
        public float screenAutoBrightnessAdjustmentOverride;

        // If true, enables automatic brightness control.
        public boolean useAutoBrightness;

        // If true, scales the brightness to a fraction of desired (as defined by
        // screenLowPowerBrightnessFactor).
        public boolean lowPowerMode;

        // The factor to adjust the screen brightness in low power mode in the range
        // 0 (screen off) to 1 (no change)
        public float screenLowPowerBrightnessFactor;

        // If true, applies a brightness boost.
        public boolean boostScreenBrightness;

        // If true, prevents the screen from completely turning on if it is currently off.
        // The display does not enter a "ready" state if this flag is true and screen on is
        // blocked.  The window manager policy blocks screen on while it prepares the keyguard to
        // prevent the user from seeing intermediate updates.
        //
        // Technically, we may not block the screen itself from turning on (because that introduces
        // extra unnecessary latency) but we do prevent content on screen from becoming
        // visible to the user.
        public boolean blockScreenOn;

        // Overrides the policy for adjusting screen brightness and state while dozing.
        public int dozeScreenState;
        public float dozeScreenBrightness;

        public DisplayPowerRequest() {
            policy = POLICY_BRIGHT;
            useProximitySensor = false;
            screenBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            useAutoBrightness = false;
            screenAutoBrightnessAdjustmentOverride = Float.NaN;
            screenLowPowerBrightnessFactor = 0.5f;
            blockScreenOn = false;
            dozeScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            dozeScreenState = Display.STATE_UNKNOWN;
        }

        public DisplayPowerRequest(DisplayPowerRequest other) {
            copyFrom(other);
        }

        public boolean isBrightOrDim() {
            return policy == POLICY_BRIGHT || policy == POLICY_DIM;
        }

        public boolean isVr() {
            return policy == POLICY_VR;
        }

        public void copyFrom(DisplayPowerRequest other) {
            policy = other.policy;
            useProximitySensor = other.useProximitySensor;
            screenBrightnessOverride = other.screenBrightnessOverride;
            useAutoBrightness = other.useAutoBrightness;
            screenAutoBrightnessAdjustmentOverride = other.screenAutoBrightnessAdjustmentOverride;
            screenLowPowerBrightnessFactor = other.screenLowPowerBrightnessFactor;
            blockScreenOn = other.blockScreenOn;
            lowPowerMode = other.lowPowerMode;
            boostScreenBrightness = other.boostScreenBrightness;
            dozeScreenBrightness = other.dozeScreenBrightness;
            dozeScreenState = other.dozeScreenState;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DisplayPowerRequest
                    && equals((DisplayPowerRequest)o);
        }

        public boolean equals(DisplayPowerRequest other) {
            return other != null
                    && policy == other.policy
                    && useProximitySensor == other.useProximitySensor
                    && floatEquals(screenBrightnessOverride,
                            other.screenBrightnessOverride)
                    && useAutoBrightness == other.useAutoBrightness
                    && floatEquals(screenAutoBrightnessAdjustmentOverride,
                            other.screenAutoBrightnessAdjustmentOverride)
                    && screenLowPowerBrightnessFactor
                    == other.screenLowPowerBrightnessFactor
                    && blockScreenOn == other.blockScreenOn
                    && lowPowerMode == other.lowPowerMode
                    && boostScreenBrightness == other.boostScreenBrightness
                    && floatEquals(dozeScreenBrightness, other.dozeScreenBrightness)
                    && dozeScreenState == other.dozeScreenState;
        }

        private boolean floatEquals(float f1, float f2) {
            return f1 == f2 || Float.isNaN(f1) && Float.isNaN(f2);
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        @Override
        public String toString() {
            return "policy=" + policyToString(policy)
                    + ", useProximitySensor=" + useProximitySensor
                    + ", screenBrightnessOverride=" + screenBrightnessOverride
                    + ", useAutoBrightness=" + useAutoBrightness
                    + ", screenAutoBrightnessAdjustmentOverride="
                    + screenAutoBrightnessAdjustmentOverride
                    + ", screenLowPowerBrightnessFactor=" + screenLowPowerBrightnessFactor
                    + ", blockScreenOn=" + blockScreenOn
                    + ", lowPowerMode=" + lowPowerMode
                    + ", boostScreenBrightness=" + boostScreenBrightness
                    + ", dozeScreenBrightness=" + dozeScreenBrightness
                    + ", dozeScreenState=" + Display.stateToString(dozeScreenState);
        }

        public static String policyToString(int policy) {
            switch (policy) {
                case POLICY_OFF:
                    return "OFF";
                case POLICY_DOZE:
                    return "DOZE";
                case POLICY_DIM:
                    return "DIM";
                case POLICY_BRIGHT:
                    return "BRIGHT";
                case POLICY_VR:
                    return "VR";
                default:
                    return Integer.toString(policy);
            }
        }
    }

    /**
     * Asynchronous callbacks from the power controller to the power manager service.
     */
    public interface DisplayPowerCallbacks {
        void onStateChanged();
        void onProximityPositive();
        void onProximityNegative();
        void onDisplayStateChange(int state); // one of the Display state constants

        void acquireSuspendBlocker();
        void releaseSuspendBlocker();
    }

    /**
     * Called within a Surface transaction whenever the size or orientation of a
     * display may have changed.  Provides an opportunity for the client to
     * update the position of its surfaces as part of the same transaction.
     */
    public interface DisplayTransactionListener {
        void onDisplayTransaction(Transaction t);
    }
}

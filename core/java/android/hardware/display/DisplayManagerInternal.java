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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.companion.virtual.IVirtualDevice;
import android.graphics.Point;
import android.hardware.SensorManager;
import android.hardware.input.HostUsiVersion;
import android.os.Handler;
import android.os.PowerManager;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.SurfaceControl.RefreshRateRange;
import android.view.SurfaceControl.Transaction;
import android.window.DisplayWindowPolicyController;
import android.window.ScreenCapture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

/**
 * Display manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class DisplayManagerInternal {

    @IntDef(prefix = {"REFRESH_RATE_LIMIT_"}, value = {
            REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RefreshRateLimitType {}

    /** Refresh rate should be limited when High Brightness Mode is active. */
    public static final int REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE = 1;

    /**
     * Called by the power manager to initialize power management facilities.
     */
    public abstract void initPowerManagement(DisplayPowerCallbacks callbacks,
            Handler handler, SensorManager sensorManager);

    /**
     * Called by the VirtualDeviceManagerService to create a VirtualDisplay owned by a
     * VirtualDevice.
     */
    public abstract int createVirtualDisplay(VirtualDisplayConfig config,
            IVirtualDisplayCallback callback, IVirtualDevice virtualDevice,
            DisplayWindowPolicyController dwpc, String packageName);

    /**
     * Called by the power manager to request a new power state.
     * <p>
     * The display power controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     * </p>
     *
     * @param groupId The identifier for the display group being requested to change power state
     * @param request The requested power state.
     * @param waitForNegativeProximity If {@code true}, issues a request to wait for
     * negative proximity before turning the screen back on, assuming the screen
     * was turned off by the proximity sensor.
     * @return {@code true} if display group is ready, {@code false} if there are important
     * changes that must be made asynchronously (such as turning the screen on), in which case
     * the caller should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged}
     * then try the request again later until the state converges. If the provided {@code groupId}
     * cannot be found then {@code true} will be returned.
     */
    public abstract boolean requestPowerState(int groupId, DisplayPowerRequest request,
            boolean waitForNegativeProximity);

    /**
     * Returns {@code true} if the proximity sensor screen-off function is available.
     */
    public abstract boolean isProximitySensorAvailable();

    /**
     * Registers a display group listener which will be informed of the addition, removal, or change
     * of display groups.
     *
     * @param listener The listener to register.
     */
    public abstract void registerDisplayGroupListener(DisplayGroupListener listener);

    /**
     * Unregisters a display group listener which will be informed of the addition, removal, or
     * change of display groups.
     *
     * @param listener The listener to unregister.
     */
    public abstract void unregisterDisplayGroupListener(DisplayGroupListener listener);

    /**
     * Screenshot for internal system-only use such as rotation, etc.  This method includes
     * secure layers and the result should never be exposed to non-system applications.
     * This method does not apply any rotation and provides the output in natural orientation.
     *
     * @param displayId The display id to take the screenshot of.
     * @return The buffer or null if we have failed.
     */
    public abstract ScreenCapture.ScreenshotHardwareBuffer systemScreenshot(int displayId);

    /**
     * General screenshot functionality that excludes secure layers and applies appropriate
     * rotation that the device is currently in.
     *
     * @param displayId The display id to take the screenshot of.
     * @return The buffer or null if we have failed.
     */
    public abstract ScreenCapture.ScreenshotHardwareBuffer userScreenshot(int displayId);

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @return The logical display info, or null if the display does not exist.  The
     * returned object must be treated as immutable.
     */
    public abstract DisplayInfo getDisplayInfo(int displayId);

    /**
     * Returns a set of DisplayInfo, for the states that may be assumed by either the given display,
     * or any other display within that display's group.
     *
     * @param displayId The logical display id to fetch DisplayInfo for.
     */
    public abstract Set<DisplayInfo> getPossibleDisplayInfo(int displayId);

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
     * @param t The default transaction.
     * @param displayTransactions The transactions mapped by display id.
     */
    public abstract void performTraversal(Transaction t,
            SparseArray<SurfaceControl.Transaction> displayTransactions);

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
     * @param requestedMinRefreshRate The preferred lowest refresh rate for the top-most visible
     *                                window that has a preference.
     * @param requestedMaxRefreshRate The preferred highest refresh rate for the top-most visible
     *                                window that has a preference.
     * @param requestedMinimalPostProcessing The preferred minimal post processing setting for the
     * display. This is true when there is at least one visible window that wants minimal post
     * processng on.
     * @param disableHdrConversion The preferred HDR conversion setting for the window.
     * @param inTraversal True if called from WindowManagerService during a window traversal
     * prior to call to performTraversalInTransactionFromWindowManager.
     */
    public abstract void setDisplayProperties(int displayId, boolean hasContent,
            float requestedRefreshRate, int requestedModeId, float requestedMinRefreshRate,
            float requestedMaxRefreshRate, boolean requestedMinimalPostProcessing,
            boolean disableHdrConversion, boolean inTraversal);

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
     * Returns the refresh rate switching type.
     */
    @DisplayManager.SwitchingType
    public abstract int getRefreshRateSwitchingType();

    /**
     * TODO: b/191384041 - Replace this with getRefreshRateLimitations()
     * Return the refresh rate restriction for the specified display and sensor pairing. If the
     * specified sensor is identified as an associated sensor in the specified display's
     * display-device-config file, then return any refresh rate restrictions that it might define.
     * If no restriction is specified, or the sensor is not associated with the display, then null
     * will be returned.
     *
     * @param displayId The display to check against.
     * @param name The name of the sensor.
     * @param type The type of sensor.
     *
     * @return The min/max refresh-rate restriction as a {@link Pair} of floats, or null if not
     * restricted.
     */
    public abstract RefreshRateRange getRefreshRateForDisplayAndSensor(
            int displayId, String name, String type);

    /**
     * Returns a list of various refresh rate limitations for the specified display.
     *
     * @param displayId The display to get limitations for.
     *
     * @return a list of {@link RefreshRateLimitation}s describing the various limits.
     */
    public abstract List<RefreshRateLimitation> getRefreshRateLimitations(int displayId);

    /**
     * For the given displayId, updates if WindowManager is responsible for mirroring on that
     * display. If {@code false}, then SurfaceFlinger performs no layer mirroring to the
     * given display.
     * Only used for mirroring started from MediaProjection.
     */
    public abstract void setWindowManagerMirroring(int displayId, boolean isMirroring);

    /**
     * Returns the default size of the surface associated with the display, or null if the surface
     * is not provided for layer mirroring by SurfaceFlinger. Size is rotated to reflect the current
     * display device orientation.
     * Used for mirroring from MediaProjection, or a physical display based on display flags.
     */
    public abstract Point getDisplaySurfaceDefaultSize(int displayId);

    /**
     * Get a new displayId which represents the display you want to mirror. If mirroring is not
     * enabled on the display, {@link Display#INVALID_DISPLAY} will be returned.
     *
     * @param displayId The id of the display.
     * @return The displayId that should be mirrored or INVALID_DISPLAY if mirroring is not enabled.
     */
    public abstract int getDisplayIdToMirror(int displayId);

    /**
     * Receives early interactivity changes from power manager.
     *
     * @param interactive The interactive state that the device is moving into.
     */
    public abstract void onEarlyInteractivityChange(boolean interactive);

    /**
     * Get {@link DisplayWindowPolicyController} associated to the {@link DisplayInfo#displayId}
     *
     * @param displayId The id of the display.
     * @return The associated {@link DisplayWindowPolicyController}.
     */
    public abstract DisplayWindowPolicyController getDisplayWindowPolicyController(int displayId);

    /**
     * Get DisplayPrimaries from SF for a particular display.
     */
    public abstract SurfaceControl.DisplayPrimaries getDisplayNativePrimaries(int displayId);

    /**
     * Get the version of the Universal Stylus Initiative (USI) Protocol supported by the display.
     * @param displayId The id of the display.
     * @return The USI version, or null if not supported
     */
    @Nullable
    public abstract HostUsiVersion getHostUsiVersion(int displayId);

    /**
     * Get the ALS data for a particular display.
     *
     * @param displayId The id of the display.
     * @return {@link AmbientLightSensorData}
     */
    @Nullable
    public abstract AmbientLightSensorData getAmbientLightSensorData(int displayId);

    /**
     * Get all available DisplayGroupIds.
     */
    public abstract IntArray getDisplayGroupIds();

    /**
     * Called upon presentation started/ended on the display.
     * @param displayId the id of the display where presentation started.
     * @param isShown whether presentation is shown.
     */
    public abstract void onPresentation(int displayId, boolean isShown);

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
    public static class DisplayPowerRequest {
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
        public int dozeScreenStateReason;

        public DisplayPowerRequest() {
            policy = POLICY_BRIGHT;
            useProximitySensor = false;
            screenBrightnessOverride = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            screenAutoBrightnessAdjustmentOverride = Float.NaN;
            screenLowPowerBrightnessFactor = 0.5f;
            blockScreenOn = false;
            dozeScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            dozeScreenState = Display.STATE_UNKNOWN;
            dozeScreenStateReason = Display.STATE_REASON_UNKNOWN;
        }

        public DisplayPowerRequest(DisplayPowerRequest other) {
            copyFrom(other);
        }

        public boolean isBrightOrDim() {
            return policy == POLICY_BRIGHT || policy == POLICY_DIM;
        }

        public void copyFrom(DisplayPowerRequest other) {
            policy = other.policy;
            useProximitySensor = other.useProximitySensor;
            screenBrightnessOverride = other.screenBrightnessOverride;
            screenAutoBrightnessAdjustmentOverride = other.screenAutoBrightnessAdjustmentOverride;
            screenLowPowerBrightnessFactor = other.screenLowPowerBrightnessFactor;
            blockScreenOn = other.blockScreenOn;
            lowPowerMode = other.lowPowerMode;
            boostScreenBrightness = other.boostScreenBrightness;
            dozeScreenBrightness = other.dozeScreenBrightness;
            dozeScreenState = other.dozeScreenState;
            dozeScreenStateReason = other.dozeScreenStateReason;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof DisplayPowerRequest
                    && equals((DisplayPowerRequest)o);
        }

        public boolean equals(DisplayPowerRequest other) {
            return other != null
                    && policy == other.policy
                    && useProximitySensor == other.useProximitySensor
                    && floatEquals(screenBrightnessOverride,
                            other.screenBrightnessOverride)
                    && floatEquals(screenAutoBrightnessAdjustmentOverride,
                            other.screenAutoBrightnessAdjustmentOverride)
                    && screenLowPowerBrightnessFactor
                    == other.screenLowPowerBrightnessFactor
                    && blockScreenOn == other.blockScreenOn
                    && lowPowerMode == other.lowPowerMode
                    && boostScreenBrightness == other.boostScreenBrightness
                    && floatEquals(dozeScreenBrightness, other.dozeScreenBrightness)
                    && dozeScreenState == other.dozeScreenState
                    && dozeScreenStateReason == other.dozeScreenStateReason;
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
                    + ", screenAutoBrightnessAdjustmentOverride="
                    + screenAutoBrightnessAdjustmentOverride
                    + ", screenLowPowerBrightnessFactor=" + screenLowPowerBrightnessFactor
                    + ", blockScreenOn=" + blockScreenOn
                    + ", lowPowerMode=" + lowPowerMode
                    + ", boostScreenBrightness=" + boostScreenBrightness
                    + ", dozeScreenBrightness=" + dozeScreenBrightness
                    + ", dozeScreenState=" + Display.stateToString(dozeScreenState)
                    + ", dozeScreenStateReason="
                            + Display.stateReasonToString(dozeScreenStateReason);
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
        void onDisplayStateChange(boolean allInactive, boolean allOff);

        /**
         * Acquires a suspend blocker with a specified label.
         *
         * @param id A logging label for the acquisition.
         */
        void acquireSuspendBlocker(String id);

        /**
         * Releases a suspend blocker with a specified label.
         *
         * @param id A logging label for the release.
         */
        void releaseSuspendBlocker(String id);
    }

    /**
     * Called within a Surface transaction whenever the size or orientation of a
     * display may have changed.  Provides an opportunity for the client to
     * update the position of its surfaces as part of the same transaction.
     */
    public interface DisplayTransactionListener {
        void onDisplayTransaction(Transaction t);
    }

    /**
     * Called when there are changes to {@link com.android.server.display.DisplayGroup
     * DisplayGroups}.
     */
    public interface DisplayGroupListener {
        /**
         * A new display group with the provided {@code groupId} was added.
         *
         * <ol>
         *     <li>The {@code groupId} is applied to all appropriate {@link Display displays}.
         *     <li>This method is called.
         *     <li>{@link android.hardware.display.DisplayManager.DisplayListener DisplayListeners}
         *     are informed of any corresponding changes.
         * </ol>
         */
        void onDisplayGroupAdded(int groupId);

        /**
         * The display group with the provided {@code groupId} was removed.
         *
         * <ol>
         *     <li>All affected {@link Display displays} have their group IDs updated appropriately.
         *     <li>{@link android.hardware.display.DisplayManager.DisplayListener DisplayListeners}
         *     are informed of any corresponding changes.
         *     <li>This method is called.
         * </ol>
         */
        void onDisplayGroupRemoved(int groupId);

        /**
         * The display group with the provided {@code groupId} has changed.
         *
         * <ol>
         *     <li>All affected {@link Display displays} have their group IDs updated appropriately.
         *     <li>{@link android.hardware.display.DisplayManager.DisplayListener DisplayListeners}
         *     are informed of any corresponding changes.
         *     <li>This method is called.
         * </ol>
         */
        void onDisplayGroupChanged(int groupId);
    }

    /**
     * Describes a limitation on a display's refresh rate. Includes the allowed refresh rate
     * range as well as information about when it applies, such as high-brightness-mode.
     */
    public static final class RefreshRateLimitation {
        @RefreshRateLimitType public int type;

        /** The range the that refresh rate should be limited to. */
        public RefreshRateRange range;

        public RefreshRateLimitation(@RefreshRateLimitType int type, float min, float max) {
            this.type = type;
            range = new RefreshRateRange(min, max);
        }

        @Override
        public String toString() {
            return "RefreshRateLimitation(" + type + ": " + range + ")";
        }
    }

    /**
     * Class to provide Ambient sensor data using the API
     * {@link DisplayManagerInternal#getAmbientLightSensorData(int)}
     */
    public static final class AmbientLightSensorData {
        public String sensorName;
        public String sensorType;

        public AmbientLightSensorData(String name, String type) {
            sensorName = name;
            sensorType = type;
        }

        @Override
        public String toString() {
            return "AmbientLightSensorData(" + sensorName + ", " + sensorType + ")";
        }
    }

    /**
     * Associate a internal display to a {@link DisplayOffloader}.
     *
     * @param displayId the id of the internal display.
     * @param displayOffloader the {@link DisplayOffloader} that controls offloading ops of internal
     *                         display whose id is displayId.
     * @return a {@link DisplayOffloadSession} associated with given displayId and displayOffloader.
     */
    public abstract DisplayOffloadSession registerDisplayOffloader(
            int displayId, DisplayOffloader displayOffloader);

    /** The callbacks that controls the entry & exit of display offloading. */
    public interface DisplayOffloader {
        boolean startOffload();

        void stopOffload();

        /**
         * Called when {@link DisplayOffloadSession} tries to block screen turning on.
         *
         * @param unblocker a {@link Runnable} executed upon all work required before screen turning
         *                  on is done.
         */
        void onBlockingScreenOn(Runnable unblocker);
    }

    /** A session token that associates a internal display with a {@link DisplayOffloader}. */
    public interface DisplayOffloadSession {
        /** Provide the display state to use in place of state DOZE. */
        void setDozeStateOverride(int displayState);

        /** Whether the session is active. */
        boolean isActive();

        /**
         * Update the brightness from the offload chip.
         * @param brightness The brightness value between {@link PowerManager.BRIGHTNESS_MIN} and
         *                   {@link PowerManager.BRIGHTNESS_MAX}, or
         *                   {@link PowerManager.BRIGHTNESS_INVALID_FLOAT} which removes
         *                   the brightness from offload. Other values will be ignored.
         */
        void updateBrightness(float brightness);

        /**
         * Called while display is turning to state ON to leave a small period for displayoffload
         * session to finish some work.
         *
         * @param unblocker a {@link Runnable} used by displayoffload session to notify
         *                  {@link DisplayManager} that it can continue turning screen on.
         */
        boolean blockScreenOn(Runnable unblocker);

        /**
         * Get the brightness levels used to determine automatic brightness based on lux levels.
         * @param mode The auto-brightness mode
         *             (AutomaticBrightnessController.AutomaticBrightnessMode)
         * @return The brightness levels for the specified mode. The values are between
         * {@link PowerManager.BRIGHTNESS_MIN} and {@link PowerManager.BRIGHTNESS_MAX}.
         */
        float[] getAutoBrightnessLevels(int mode);

        /**
         * Get the lux levels used to determine automatic brightness.
         * @param mode The auto-brightness mode
         *             (AutomaticBrightnessController.AutomaticBrightnessMode)
         * @return The lux levels for the specified mode
         */
        float[] getAutoBrightnessLuxLevels(int mode);

        /**
         * @return The current brightness setting
         */
        float getBrightness();

        /**
         * @return The brightness value that is used when the device is in doze
         */
        float getDozeBrightness();

        /** Returns whether displayoffload supports the given display state. */
        static boolean isSupportedOffloadState(int displayState) {
            return Display.isSuspendedState(displayState);
        }
    }
}

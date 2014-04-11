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

import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.view.DisplayInfo;

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
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @return The logical display info, or null if the display does not exist.  The
     * returned object must be treated as immutable.
     */
    public abstract DisplayInfo getDisplayInfo(int displayId);

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
     * Called by the window manager to perform traversals while holding a
     * surface flinger transaction.
     */
    public abstract void performTraversalInTransactionFromWindowManager();

    /**
     * Tells the display manager whether there is interesting unique content on the
     * specified logical display.  This is used to control automatic mirroring.
     * <p>
     * If the display has unique content, then the display manager arranges for it
     * to be presented on a physical display if appropriate.  Otherwise, the display manager
     * may choose to make the physical display mirror some other logical display.
     * </p>
     *
     * @param displayId The logical display id to update.
     * @param hasContent True if the logical display has content.
     * @param inTraversal True if called from WindowManagerService during a window traversal
     * prior to call to performTraversalInTransactionFromWindowManager.
     */
    public abstract void setDisplayHasContent(int displayId, boolean hasContent,
            boolean inTraversal);

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
        public static final int SCREEN_STATE_OFF = 0;
        public static final int SCREEN_STATE_DOZE = 1;
        public static final int SCREEN_STATE_DIM = 2;
        public static final int SCREEN_STATE_BRIGHT = 3;

        // The requested minimum screen power state: off, doze, dim or bright.
        public int screenState;

        // If true, the proximity sensor overrides the screen state when an object is
        // nearby, turning it off temporarily until the object is moved away.
        public boolean useProximitySensor;

        // The desired screen brightness in the range 0 (minimum / off) to 255 (brightest).
        // The display power controller may choose to clamp the brightness.
        // When auto-brightness is enabled, this field should specify a nominal default
        // value to use while waiting for the light sensor to report enough data.
        public int screenBrightness;

        // The screen auto-brightness adjustment factor in the range -1 (dimmer) to 1 (brighter).
        public float screenAutoBrightnessAdjustment;

        // If true, enables automatic brightness control.
        public boolean useAutoBrightness;

        // If true, prevents the screen from completely turning on if it is currently off.
        // The display does not enter a "ready" state if this flag is true and screen on is
        // blocked.  The window manager policy blocks screen on while it prepares the keyguard to
        // prevent the user from seeing intermediate updates.
        //
        // Technically, we may not block the screen itself from turning on (because that introduces
        // extra unnecessary latency) but we do prevent content on screen from becoming
        // visible to the user.
        public boolean blockScreenOn;

        public DisplayPowerRequest() {
            screenState = SCREEN_STATE_BRIGHT;
            useProximitySensor = false;
            screenBrightness = PowerManager.BRIGHTNESS_ON;
            screenAutoBrightnessAdjustment = 0.0f;
            useAutoBrightness = false;
            blockScreenOn = false;
        }

        public DisplayPowerRequest(DisplayPowerRequest other) {
            copyFrom(other);
        }

        // Returns true if we want the screen on in any mode, including doze.
        public boolean wantScreenOnAny() {
            return screenState != SCREEN_STATE_OFF;
        }

        // Returns true if we want the screen on in a normal mode, excluding doze.
        // This is usually what we want to tell the rest of the system.  For compatibility
        // reasons, we pretend the screen is off when dozing.
        public boolean wantScreenOnNormal() {
            return screenState == SCREEN_STATE_DIM || screenState == SCREEN_STATE_BRIGHT;
        }

        public boolean wantLightSensorEnabled() {
            // Specifically, we don't want the light sensor while dozing.
            return useAutoBrightness && wantScreenOnNormal();
        }

        public void copyFrom(DisplayPowerRequest other) {
            screenState = other.screenState;
            useProximitySensor = other.useProximitySensor;
            screenBrightness = other.screenBrightness;
            screenAutoBrightnessAdjustment = other.screenAutoBrightnessAdjustment;
            useAutoBrightness = other.useAutoBrightness;
            blockScreenOn = other.blockScreenOn;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DisplayPowerRequest
                    && equals((DisplayPowerRequest)o);
        }

        public boolean equals(DisplayPowerRequest other) {
            return other != null
                    && screenState == other.screenState
                    && useProximitySensor == other.useProximitySensor
                    && screenBrightness == other.screenBrightness
                    && screenAutoBrightnessAdjustment == other.screenAutoBrightnessAdjustment
                    && useAutoBrightness == other.useAutoBrightness
                    && blockScreenOn == other.blockScreenOn;
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        @Override
        public String toString() {
            return "screenState=" + screenState
                    + ", useProximitySensor=" + useProximitySensor
                    + ", screenBrightness=" + screenBrightness
                    + ", screenAutoBrightnessAdjustment=" + screenAutoBrightnessAdjustment
                    + ", useAutoBrightness=" + useAutoBrightness
                    + ", blockScreenOn=" + blockScreenOn;
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
        void onDisplayTransaction();
    }
}

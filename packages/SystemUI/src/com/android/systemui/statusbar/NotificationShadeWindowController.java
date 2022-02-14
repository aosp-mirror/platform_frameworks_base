/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.phone.StatusBarWindowCallback;

import java.util.function.Consumer;

/**
 * Interface to control the state of the notification shade window. Not all methods of this
 * interface will be used by each implementation of {@link NotificationShadeWindowController}.
 */
public interface NotificationShadeWindowController extends RemoteInputController.Callback {

    /**
     * Registers a {@link StatusBarWindowCallback} to receive notifications about status bar
     * window state changes.
     */
    default void registerCallback(StatusBarWindowCallback callback) {}

    /**
     * Unregisters a {@link StatusBarWindowCallback previous registered with
     * {@link #registerCallback(StatusBarWindowCallback)}}
     */
    default void unregisterCallback(StatusBarWindowCallback callback) {}

    /** Notifies the registered {@link StatusBarWindowCallback} instances. */
    default void notifyStateChangedCallbacks() {}

    /**
     * Registers a listener to monitor scrims visibility.
     *
     * @param listener A listener to monitor scrims visibility
     */
    default void setScrimsVisibilityListener(Consumer<Integer> listener) {}

    /**
     * Adds the notification shade view to the window manager.
     */
    default void attach() {}

    /** Sets the notification shade view. */
    default void setNotificationShadeView(ViewGroup view) {}

    /** Gets the notification shade view. */
    @Nullable
    default ViewGroup getNotificationShadeView() {
        return null;
    }

    /** Sets the state of whether the keyguard is currently showing or not. */
    default void setKeyguardShowing(boolean showing) {}

    /** Sets the state of whether the keyguard is currently occluded or not. */
    default void setKeyguardOccluded(boolean occluded) {}

    /** Sets the state of whether the keyguard is currently needs input or not. */
    default void setKeyguardNeedsInput(boolean needsInput) {}

    /** Sets the state of whether the notification shade panel is currently visible or not. */
    default void setPanelVisible(boolean visible) {}

    /** Sets the state of whether the notification shade is focusable or not. */
    default void setNotificationShadeFocusable(boolean focusable) {}

    /** Sets the state of whether the bouncer is showing or not. */
    default void setBouncerShowing(boolean showing) {}

    /** Sets the state of whether the backdrop is showing or not. */
    default void setBackdropShowing(boolean showing) {}

    /** Sets the state of whether the keyguard is fading away or not. */
    default void setKeyguardFadingAway(boolean keyguardFadingAway) {}

    /** Sets the state of whether the quick settings is expanded or not. */
    default void setQsExpanded(boolean expanded) {}

    /** Sets the state of whether the user activities are forced or not. */
    default void setForceUserActivity(boolean forceUserActivity) {}

    /** Sets the state of whether an activity is launching or not. */
    default void setLaunchingActivity(boolean launching) {}

    /** Get whether an activity is launching or not. */
    default boolean isLaunchingActivity() {
        return false;
    }

    /** Sets the state of whether the scrim is visible or not. */
    default void setScrimsVisibility(int scrimsVisibility) {}

    /** Sets the background blur radius of the notification shade window. */
    default void setBackgroundBlurRadius(int backgroundBlurRadius) {}

    /** Sets the state of whether heads up is showing or not. */
    default void setHeadsUpShowing(boolean showing) {}

    /** Sets whether the wallpaper supports ambient mode or not. */
    default void setWallpaperSupportsAmbientMode(boolean supportsAmbientMode) {}

    /** Gets whether the wallpaper is showing or not. */
    default boolean isShowingWallpaper() {
        return false;
    }

    /** Sets whether the window was collapsed by force or not. */
    default void setForceWindowCollapsed(boolean force) {}

    /** Sets whether panel is expanded or not. */
    default void setPanelExpanded(boolean isExpanded) {}

    /** Gets whether the panel is expanded or not. */
    default boolean getPanelExpanded() {
        return false;
    }

    /** Sets the state of whether the remote input is active or not. */
    default void onRemoteInputActive(boolean remoteInputActive) {}

    /** Sets the screen brightness level for when the device is dozing. */
    default void setDozeScreenBrightness(int value) {}

    /**
     * Sets whether the screen brightness is forced to the value we use for doze mode by the status
     * bar window. No-op if the device does not support dozing.
     */
    default void setForceDozeBrightness(boolean forceDozeBrightness) {}

    /** Sets the state of whether sysui is dozing or not. */
    default void setDozing(boolean dozing) {}

    /** Sets the state of whether plugin open is forced or not. */
    default void setForcePluginOpen(boolean forcePluginOpen, Object token) {}

    /** Gets whether we are forcing plugin open or not. */
    default boolean getForcePluginOpen() {
        return false;
    }

    /** Sets the state of whether the notification shade is touchable or not. */
    default void setNotTouchable(boolean notTouchable) {}

    /** Sets a {@link OtherwisedCollapsedListener}. */
    default void setStateListener(OtherwisedCollapsedListener listener) {}

    /** Sets a {@link ForcePluginOpenListener}. */
    default void setForcePluginOpenListener(ForcePluginOpenListener listener) {}

    /** Sets whether the system is in a state where the keyguard is going away. */
    default void setKeyguardGoingAway(boolean goingAway) {}

    /**
     * SystemUI may need top-ui to avoid jank when performing animations.  After the
     * animation is performed, the component should remove itself from the list of features that
     * are forcing SystemUI to be top-ui.
     */
    default void setRequestTopUi(boolean requestTopUi, String componentTag) {}

    /**
     * Under low light conditions, we might want to increase the display brightness on devices that
     * don't have an IR camera.
     * @param brightness float from 0 to 1 or {@code LayoutParams.BRIGHTNESS_OVERRIDE_NONE}
     */
    default void setFaceAuthDisplayBrightness(float brightness) {}

    /**
     * If {@link LightRevealScrim} obscures the UI.
     * @param opaque if the scrim is opaque
     */
    default void setLightRevealScrimOpaque(boolean opaque) {}

    /**
     * Custom listener to pipe data back to plugins about whether or not the status bar would be
     * collapsed if not for the plugin.
     * TODO: Find cleaner way to do this.
     */
    interface OtherwisedCollapsedListener {
        void setWouldOtherwiseCollapse(boolean otherwiseCollapse);
    }

    /**
     * Listener to indicate forcePluginOpen has changed
     */
    interface ForcePluginOpenListener {
        /**
         * Called when mState.forcePluginOpen is changed
         */
        void onChange(boolean forceOpen);
    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.input;

import static com.android.input.flags.Flags.rateLimitUserActivityPokeInDispatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.input.InputSettings;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewConfiguration;

import java.util.Map;
import java.util.function.Consumer;

/** Observes settings changes and propagates them to the native side. */
class InputSettingsObserver extends ContentObserver {
    static final String TAG = "InputManager";

    /** Feature flag name for the deep press feature */
    private static final String DEEP_PRESS_ENABLED = "deep_press_enabled";

    private final Context mContext;
    private final Handler mHandler;
    private final InputManagerService mService;
    private final NativeInputManagerService mNative;
    private final Map<Uri, Consumer<String /* reason*/>> mObservers;

    InputSettingsObserver(Context context, Handler handler, InputManagerService service,
            NativeInputManagerService nativeIms) {
        super(handler);
        mContext = context;
        mHandler = handler;
        mService = service;
        mNative = nativeIms;
        mObservers = Map.ofEntries(
                Map.entry(Settings.System.getUriFor(Settings.System.POINTER_SPEED),
                        (reason) -> updateMousePointerSpeed()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_POINTER_SPEED),
                        (reason) -> updateTouchpadPointerSpeed()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_NATURAL_SCROLLING),
                        (reason) -> updateTouchpadNaturalScrollingEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_TAP_TO_CLICK),
                        (reason) -> updateTouchpadTapToClickEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_TAP_DRAGGING),
                        (reason) -> updateTouchpadTapDraggingEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_RIGHT_CLICK_ZONE),
                        (reason) -> updateTouchpadRightClickZoneEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.SHOW_TOUCHES),
                        (reason) -> updateShowTouches()),
                Map.entry(Settings.System.getUriFor(Settings.System.POINTER_LOCATION),
                        (reason) -> updatePointerLocation()),
                Map.entry(
                        Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON),
                        (reason) -> updateAccessibilityLargePointer()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.LONG_PRESS_TIMEOUT),
                        (reason) -> updateLongPressTimeout(reason)),
                Map.entry(
                        Settings.Global.getUriFor(
                                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH),
                        (reason) -> updateMaximumObscuringOpacityForTouch()),
                Map.entry(Settings.System.getUriFor(Settings.System.SHOW_KEY_PRESSES),
                        (reason) -> updateShowKeyPresses()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.KEY_REPEAT_TIMEOUT_MS),
                        (reason) -> updateKeyRepeatInfo()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.KEY_REPEAT_DELAY_MS),
                        (reason) -> updateKeyRepeatInfo()),
                Map.entry(Settings.System.getUriFor(Settings.System.SHOW_ROTARY_INPUT),
                        (reason) -> updateShowRotaryInput()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_BOUNCE_KEYS),
                        (reason) -> updateAccessibilityBounceKeys()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SLOW_KEYS),
                        (reason) -> updateAccessibilitySlowKeys()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_STICKY_KEYS),
                        (reason) -> updateAccessibilityStickyKeys()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.STYLUS_POINTER_ICON_ENABLED),
                        (reason) -> updateStylusPointerIconEnabled()));
    }

    /**
     * Registers observers for input-related settings and updates the input subsystem with their
     * current values.
     */
    public void registerAndUpdate() {
        for (Uri uri : mObservers.keySet()) {
            mContext.getContentResolver().registerContentObserver(
                    uri, true /* notifyForDescendants */, this, UserHandle.USER_ALL);
        }

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                for (Consumer<String> observer : mObservers.values()) {
                    observer.accept("user switched");
                }
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        for (Consumer<String> observer : mObservers.values()) {
            observer.accept("just booted");
        }

        configureUserActivityPokeInterval();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        mObservers.get(uri).accept("setting changed");
    }

    private boolean getBoolean(String settingName, boolean defaultValue) {
        final int setting = Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, defaultValue ? 1 : 0, UserHandle.USER_CURRENT);
        return setting != 0;
    }

    private int constrainPointerSpeedValue(int speed) {
        return Math.min(Math.max(speed, InputSettings.MIN_POINTER_SPEED),
                InputSettings.MAX_POINTER_SPEED);
    }

    private void updateMousePointerSpeed() {
        int speed = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.POINTER_SPEED, InputSettings.DEFAULT_POINTER_SPEED,
                UserHandle.USER_CURRENT);
        mNative.setPointerSpeed(constrainPointerSpeedValue(speed));
    }

    private void updateTouchpadPointerSpeed() {
        mNative.setTouchpadPointerSpeed(
                constrainPointerSpeedValue(InputSettings.getTouchpadPointerSpeed(mContext)));
    }

    private void updateTouchpadNaturalScrollingEnabled() {
        mNative.setTouchpadNaturalScrollingEnabled(
                InputSettings.useTouchpadNaturalScrolling(mContext));
    }

    private void updateTouchpadTapToClickEnabled() {
        mNative.setTouchpadTapToClickEnabled(InputSettings.useTouchpadTapToClick(mContext));
    }

    private void updateTouchpadTapDraggingEnabled() {
        mNative.setTouchpadTapDraggingEnabled(InputSettings.useTouchpadTapDragging(mContext));
    }

    private void updateTouchpadRightClickZoneEnabled() {
        mNative.setTouchpadRightClickZoneEnabled(InputSettings.useTouchpadRightClickZone(mContext));
    }

    private void updateShowTouches() {
        mNative.setShowTouches(getBoolean(Settings.System.SHOW_TOUCHES, false));
    }

    private void updatePointerLocation() {
        mService.updatePointerLocationEnabled(
                getBoolean(Settings.System.POINTER_LOCATION, false));
    }

    private void updateShowKeyPresses() {
        mService.updateShowKeyPresses(getBoolean(Settings.System.SHOW_KEY_PRESSES, false));
    }

    private void updateShowRotaryInput() {
        mService.updateShowRotaryInput(getBoolean(Settings.System.SHOW_ROTARY_INPUT, false));
    }

    private void updateAccessibilityLargePointer() {
        final int accessibilityConfig = Settings.Secure.getIntForUser(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
                0, UserHandle.USER_CURRENT);
        mService.setUseLargePointerIcons(accessibilityConfig == 1);
    }

    private void updateLongPressTimeout(String reason) {
        // Not using ViewConfiguration.getLongPressTimeout here because it may return a stale value.
        final int longPressTimeoutMs = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LONG_PRESS_TIMEOUT, ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT,
                UserHandle.USER_CURRENT);

        final boolean featureEnabledFlag =
                DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT,
                        DEEP_PRESS_ENABLED, true /* default */);
        final boolean enabled =
                featureEnabledFlag
                        && longPressTimeoutMs <= ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;
        Log.i(TAG, (enabled ? "Enabling" : "Disabling") + " motion classifier because " + reason
                + ": feature " + (featureEnabledFlag ? "enabled" : "disabled")
                + ", long press timeout = " + longPressTimeoutMs + " ms");
        mNative.setMotionClassifierEnabled(enabled);
    }

    private void updateKeyRepeatInfo() {
        // Use ViewConfiguration getters only as fallbacks because they may return stale values.
        final int timeoutMs = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.KEY_REPEAT_TIMEOUT_MS, ViewConfiguration.getKeyRepeatTimeout(),
                UserHandle.USER_CURRENT);
        final int delayMs = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.KEY_REPEAT_DELAY_MS, ViewConfiguration.getKeyRepeatDelay(),
                UserHandle.USER_CURRENT);
        mNative.setKeyRepeatConfiguration(timeoutMs, delayMs);
    }

    private void updateMaximumObscuringOpacityForTouch() {
        final float opacity = InputSettings.getMaximumObscuringOpacityForTouch(mContext);
        if (opacity < 0 || opacity > 1) {
            Log.e(TAG, "Invalid maximum obscuring opacity " + opacity
                    + ", it should be >= 0 and <= 1, rejecting update.");
            return;
        }
        mNative.setMaximumObscuringOpacityForTouch(opacity);
    }

    private void updateAccessibilityBounceKeys() {
        mService.setAccessibilityBounceKeysThreshold(
                InputSettings.getAccessibilityBounceKeysThreshold(mContext));
    }

    private void updateAccessibilitySlowKeys() {
        mService.setAccessibilitySlowKeysThreshold(
                InputSettings.getAccessibilitySlowKeysThreshold(mContext));
    }

    private void updateAccessibilityStickyKeys() {
        mService.setAccessibilityStickyKeysEnabled(
                InputSettings.isAccessibilityStickyKeysEnabled(mContext));
    }

    private void configureUserActivityPokeInterval() {
        if (rateLimitUserActivityPokeInDispatcher()) {
            final int intervalMillis = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_minMillisBetweenInputUserActivityEvents);
            Log.i(TAG, "Setting user activity interval (ms) of " + intervalMillis);
            mNative.setMinTimeBetweenUserActivityPokes(intervalMillis);
        }
    }

    private void updateStylusPointerIconEnabled() {
        mNative.setStylusPointerIconEnabled(
                InputSettings.isStylusPointerIconEnabled(mContext, true /* forceReloadSetting */));
    }
}

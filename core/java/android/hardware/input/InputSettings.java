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

package android.hardware.input;

import android.Manifest;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.sysprop.InputProperties;

/**
 * InputSettings encapsulates reading and writing settings related to input
 *
 * @hide
 */
@TestApi
public class InputSettings {
    /**
     * Pointer Speed: The minimum (slowest) pointer speed (-7).
     * @hide
     */
    public static final int MIN_POINTER_SPEED = -7;

    /**
     * Pointer Speed: The maximum (fastest) pointer speed (7).
     * @hide
     */
    public static final int MAX_POINTER_SPEED = 7;

    /**
     * Pointer Speed: The default pointer speed (0).
     */
    @SuppressLint("UnflaggedApi") // TestApi without associated feature.
    public static final int DEFAULT_POINTER_SPEED = 0;

    /**
     * The maximum allowed obscuring opacity by UID to propagate touches (0 <= x <= 1).
     * @hide
     */
    public static final float DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH = .8f;


    private InputSettings() {
    }

    /**
     * Gets the mouse pointer speed.
     * <p>
     * Only returns the permanent mouse pointer speed.  Ignores any temporary pointer
     * speed set by {@link InputManager#tryPointerSpeed}.
     * </p>
     *
     * @param context The application context.
     * @return The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    @SuppressLint("NonUserGetterCalled")
    public static int getPointerSpeed(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.POINTER_SPEED, DEFAULT_POINTER_SPEED);
    }

    /**
     * Sets the mouse pointer speed.
     * <p>
     * Requires {@link android.Manifest.permission#WRITE_SETTINGS}.
     * </p>
     *
     * @param context The application context.
     * @param speed The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public static void setPointerSpeed(Context context, int speed) {
        if (speed < MIN_POINTER_SPEED || speed > MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        Settings.System.putInt(context.getContentResolver(),
                Settings.System.POINTER_SPEED, speed);
    }

    /**
     * Returns the maximum allowed obscuring opacity per UID to propagate touches.
     *
     * <p>For certain window types (e.g. {@link LayoutParams#TYPE_APPLICATION_OVERLAY}),
     * the decision of honoring {@link LayoutParams#FLAG_NOT_TOUCHABLE} or not depends on
     * the combined obscuring opacity of the windows above the touch-consuming window, per
     * UID. Check documentation of {@link LayoutParams#FLAG_NOT_TOUCHABLE} for more details.
     *
     * <p>The value returned is between 0 (inclusive) and 1 (inclusive).
     *
     * @see LayoutParams#FLAG_NOT_TOUCHABLE
     *
     * @hide
     */
    @FloatRange(from = 0, to = 1)
    public static float getMaximumObscuringOpacityForTouch(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH,
                DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH);
    }

    /**
     * Sets the maximum allowed obscuring opacity by UID to propagate touches.
     *
     * <p>For certain window types (e.g. SAWs), the decision of honoring {@link LayoutParams
     * #FLAG_NOT_TOUCHABLE} or not depends on the combined obscuring opacity of the windows
     * above the touch-consuming window.
     *
     * <p>For a certain UID:
     * <ul>
     *     <li>If it's the same as the UID of the touch-consuming window, allow it to propagate
     *     the touch.
     *     <li>Otherwise take all its windows of eligible window types above the touch-consuming
     *     window, compute their combined obscuring opacity considering that {@code
     *     opacity(A, B) = 1 - (1 - opacity(A))*(1 - opacity(B))}. If the computed value is
     *     less than or equal to this setting and there are no other windows preventing the
     *     touch, allow the UID to propagate the touch.
     * </ul>
     *
     * <p>This value should be between 0 (inclusive) and 1 (inclusive).
     *
     * @see #getMaximumObscuringOpacityForTouch(Context)
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public static void setMaximumObscuringOpacityForTouch(
            @NonNull Context context,
            @FloatRange(from = 0, to = 1) float opacity) {
        if (opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException(
                    "Maximum obscuring opacity for touch should be >= 0 and <= 1");
        }
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH, opacity);
    }

    /**
     * Whether stylus has ever been used on device (false by default).
     * @hide
     */
    public static boolean isStylusEverUsed(@NonNull Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.STYLUS_EVER_USED, 0) == 1;
    }

    /**
     * Set whether stylus has ever been used on device.
     * Should only ever be set to true once after stylus first usage.
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public static void setStylusEverUsed(@NonNull Context context, boolean stylusEverUsed) {
        Settings.Global.putInt(context.getContentResolver(),
                Settings.Global.STYLUS_EVER_USED, stylusEverUsed ? 1 : 0);
    }


    /**
     * Gets the touchpad pointer speed.
     *
     * The returned value only applies to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @return The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    public static int getTouchpadPointerSpeed(@NonNull Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_POINTER_SPEED, DEFAULT_POINTER_SPEED,
                UserHandle.USER_CURRENT);
    }

    /**
     * Sets the touchpad pointer speed, and saves it in the settings.
     *
     * The new speed will only apply to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @param speed The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public static void setTouchpadPointerSpeed(@NonNull Context context, int speed) {
        if (speed < MIN_POINTER_SPEED || speed > MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_POINTER_SPEED, speed, UserHandle.USER_CURRENT);
    }

    /**
     * Returns true if moving two fingers upwards on the touchpad should
     * scroll down, which is known as natural scrolling.
     *
     * The returned value only applies to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @return Whether the touchpad should use natural scrolling.
     *
     * @hide
     */
    public static boolean useTouchpadNaturalScrolling(@NonNull Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_NATURAL_SCROLLING, 1, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * Sets the natural scroll behavior for the touchpad.
     *
     * If natural scrolling is enabled, moving two fingers upwards on the
     * touchpad will scroll down.
     *
     * @param context The application context.
     * @param enabled Will enable natural scroll if true, disable it if false
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public static void setTouchpadNaturalScrolling(@NonNull Context context, boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_NATURAL_SCROLLING, enabled ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    /**
     * Returns true if the touchpad should use tap to click.
     *
     * The returned value only applies to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @return Whether the touchpad should use tap to click.
     *
     * @hide
     */
    public static boolean useTouchpadTapToClick(@NonNull Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_TAP_TO_CLICK, 0, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * Sets the tap to click behavior for the touchpad.
     *
     * The new behavior is only applied to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @param enabled Will enable tap to click if true, disable it if false
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public static void setTouchpadTapToClick(@NonNull Context context, boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_TAP_TO_CLICK, enabled ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    /**
     * Returns true if the touchpad should use the right click zone.
     *
     * The returned value only applies to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @return Whether the touchpad should use the right click zone.
     *
     * @hide
     */
    public static boolean useTouchpadRightClickZone(@NonNull Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_RIGHT_CLICK_ZONE, 0, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * Sets the right click zone behavior for the touchpad.
     *
     * The new behavior is only applied to gesture-compatible touchpads.
     *
     * @param context The application context.
     * @param enabled Will enable the right click zone if true, disable it if false
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SETTINGS)
    public static void setTouchpadRightClickZone(@NonNull Context context, boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.TOUCHPAD_RIGHT_CLICK_ZONE, enabled ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    /**
     * Whether a pointer icon will be shown over the location of a
     * stylus pointer.
     * @hide
     */
    public static boolean isStylusPointerIconEnabled(@NonNull Context context) {
        return context.getResources()
                       .getBoolean(com.android.internal.R.bool.config_enableStylusPointerIcon)
               || InputProperties.force_enable_stylus_pointer_icon().orElse(false);
    }
}

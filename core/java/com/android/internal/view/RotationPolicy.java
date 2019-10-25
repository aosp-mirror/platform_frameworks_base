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

package com.android.internal.view;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Point;
import android.hardware.configstore.V1_1.DisplayOrientation;
import android.hardware.configstore.V1_1.ISurfaceFlingerConfigs;
import android.hardware.configstore.V1_1.OptionalDisplayOrientation;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManagerGlobal;

import com.android.internal.R;

import java.util.NoSuchElementException;

/**
 * Provides helper functions for configuring the display rotation policy.
 */
public final class RotationPolicy {
    private static final String TAG = "RotationPolicy";
    private static final int CURRENT_ROTATION = -1;

    private static int sNaturalRotation = -1;

    private RotationPolicy() {
    }

    /**
     * Gets whether the device supports rotation. In general such a
     * device has an accelerometer and has the portrait and landscape
     * features.
     *
     * @param context Context for accessing system resources.
     * @return Whether the device supports rotation.
     */
    public static boolean isRotationSupported(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
                && pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT)
                && pm.hasSystemFeature(PackageManager.FEATURE_SCREEN_LANDSCAPE)
                && context.getResources().getBoolean(
                        com.android.internal.R.bool.config_supportAutoRotation);
    }

    /**
     * Returns the orientation that will be used when locking the orientation from system UI
     * with {@link #setRotationLock}.
     *
     * If the device only supports locking to its natural orientation, this will be either
     * Configuration.ORIENTATION_PORTRAIT or Configuration.ORIENTATION_LANDSCAPE,
     * otherwise Configuration.ORIENTATION_UNDEFINED if any orientation is lockable.
     */
    public static int getRotationLockOrientation(Context context) {
        if (!isCurrentRotationAllowed(context)) {
            final Point size = new Point();
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            try {
                final Display display = context.getDisplay();
                final int displayId = display != null
                        ? display.getDisplayId()
                        : Display.DEFAULT_DISPLAY;
                wm.getInitialDisplaySize(displayId, size);
                return size.x < size.y ?
                        Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to get the display size");
            }
        }
        return Configuration.ORIENTATION_UNDEFINED;
    }

    /**
     * Returns true if the rotation-lock toggle should be shown in system UI.
     */
    public static boolean isRotationLockToggleVisible(Context context) {
        return isRotationSupported(context) &&
                Settings.System.getIntForUser(context.getContentResolver(),
                        Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0,
                        UserHandle.USER_CURRENT) == 0;
    }

    /**
     * Returns true if rotation lock is enabled.
     */
    public static boolean isRotationLocked(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) == 0;
    }

    /**
     * Enables or disables rotation lock from the system UI toggle.
     */
    public static void setRotationLock(Context context, final boolean enabled) {
        final int rotation = isCurrentRotationAllowed(context)
                ? CURRENT_ROTATION : getNaturalRotation();
        setRotationLockAtAngle(context, enabled, rotation);
    }

    /**
     * Enables or disables rotation lock at a specific rotation from system UI.
     */
    public static void setRotationLockAtAngle(Context context, final boolean enabled,
            final int rotation) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0,
                UserHandle.USER_CURRENT);

        setRotationLock(enabled, rotation);
    }

    /**
     * Enables or disables natural rotation lock from Accessibility settings.
     *
     * If rotation is locked for accessibility, the system UI toggle is hidden to avoid confusion.
     */
    public static void setRotationLockForAccessibility(Context context, final boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, enabled ? 1 : 0,
                        UserHandle.USER_CURRENT);

        setRotationLock(enabled, getNaturalRotation());
    }

    public static boolean isRotationAllowed(int rotation,
            int userRotationAngles, boolean allowAllRotations) {
        if (userRotationAngles < 0) {
            // Not set by user so use these defaults
            userRotationAngles = allowAllRotations ?
                    (1 | 2 | 4 | 8) : // All angles
                    (1 | 2 | 8); // All except 180
        }
        switch (rotation) {
            case Surface.ROTATION_0:
                return (userRotationAngles & 1) != 0;
            case Surface.ROTATION_90:
                return (userRotationAngles & 2) != 0;
            case Surface.ROTATION_180:
                return (userRotationAngles & 4) != 0;
            case Surface.ROTATION_270:
                return (userRotationAngles & 8) != 0;
        }
        return false;
    }

    private static boolean isCurrentRotationAllowed(Context context) {
        int userRotationAngles = Settings.System.getInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION_ANGLES, -1);
        boolean allowAllRotations = context.getResources().getBoolean(
                com.android.internal.R.bool.config_allowAllRotations);
        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            return isRotationAllowed(wm.getDefaultDisplayRotation(), userRotationAngles,
                    allowAllRotations);
        } catch (RemoteException exc) {
            Log.w(TAG, "Unable to getWindowManagerService.getDefaultDisplayRotation()");
        }
        return false;
    }

    private static void setRotationLock(final boolean enabled, final int rotation) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    if (enabled) {
                        wm.freezeRotation(rotation);
                    } else {
                        wm.thawRotation();
                    }
                } catch (RemoteException exc) {
                    Log.w(TAG, "Unable to save auto-rotate setting");
                }
            }
        });
    }

    /**
     * Registers a listener for rotation policy changes affecting the caller's user
     */
    public static void registerRotationPolicyListener(Context context,
            RotationPolicyListener listener) {
        registerRotationPolicyListener(context, listener, UserHandle.getCallingUserId());
    }

    /**
     * Registers a listener for rotation policy changes affecting a specific user,
     * or USER_ALL for all users.
     */
    public static void registerRotationPolicyListener(Context context,
            RotationPolicyListener listener, int userHandle) {
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.ACCELEROMETER_ROTATION),
                false, listener.mObserver, userHandle);
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY),
                false, listener.mObserver, userHandle);
    }

    /**
     * Unregisters a listener for rotation policy changes.
     */
    public static void unregisterRotationPolicyListener(Context context,
            RotationPolicyListener listener) {
        context.getContentResolver().unregisterContentObserver(listener.mObserver);
    }

    public static int getNaturalRotation() {
        if (sNaturalRotation == -1) {
            sNaturalRotation = getNaturalRotationConfig();
        }
        return sNaturalRotation;
    }

    private static int getNaturalRotationConfig() {
        String primaryDisplayOrientation =
                SystemProperties.get("ro.surface_flinger.primary_display_orientation");
        if (primaryDisplayOrientation == "ORIENTATION_90") {
            return Surface.ROTATION_90;
        }
        if (primaryDisplayOrientation == "ORIENTATION_180") {
            return Surface.ROTATION_180;
        }
        if (primaryDisplayOrientation == "ORIENTATION_270") {
            return Surface.ROTATION_270;
        }

        OptionalDisplayOrientation orientation;

        try {
            orientation =
                    ISurfaceFlingerConfigs.getService().primaryDisplayOrientation();
            switch (orientation.value) {
                case DisplayOrientation.ORIENTATION_90:
                    return Surface.ROTATION_90;
                case DisplayOrientation.ORIENTATION_180:
                    return Surface.ROTATION_180;
                case DisplayOrientation.ORIENTATION_270:
                    return Surface.ROTATION_270;
            }
        } catch (RemoteException | NoSuchElementException e) {
            // do nothing
        }

        return Surface.ROTATION_0;
    }

    /**
     * Listener that is invoked whenever a change occurs that might affect the rotation policy.
     */
    public static abstract class RotationPolicyListener {
        final ContentObserver mObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                RotationPolicyListener.this.onChange();
            }
        };

        public abstract void onChange();
    }
}
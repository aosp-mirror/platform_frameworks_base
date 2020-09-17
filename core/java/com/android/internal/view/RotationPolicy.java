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
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManagerGlobal;

import com.android.internal.R;

/**
 * Provides helper functions for configuring the display rotation policy.
 */
public final class RotationPolicy {
    private static final String TAG = "RotationPolicy";
    private static final int CURRENT_ROTATION = -1;

    public static final int NATURAL_ROTATION = Surface.ROTATION_0;

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
        if (!areAllRotationsAllowed(context)) {
            final Point size = new Point();
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            try {
                final int displayId = context.getDisplayId();
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
        final int rotation = areAllRotationsAllowed(context) ? CURRENT_ROTATION : NATURAL_ROTATION;
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

        setRotationLock(enabled, NATURAL_ROTATION);
    }

    private static boolean areAllRotationsAllowed(Context context) {
        return context.getResources().getBoolean(R.bool.config_allowAllRotations);
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
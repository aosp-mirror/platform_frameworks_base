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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManagerGlobal;

/**
 * Provides helper functions for configuring the display rotation policy.
 */
public final class RotationPolicy {
    private static final String TAG = "RotationPolicy";

    private RotationPolicy() {
    }

    /**
     * Returns true if the device supports the rotation-lock toggle feature
     * in the system UI or system bar.
     *
     * When the rotation-lock toggle is supported, the "auto-rotate screen" option in
     * Display settings should be hidden, but it should remain available in Accessibility
     * settings.
     */
    public static boolean isRotationLockToggleSupported(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /**
     * Returns true if the rotation-lock toggle should be shown in the UI.
     */
    public static boolean isRotationLockToggleVisible(Context context) {
        return isRotationLockToggleSupported(context) &&
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
     * Enables or disables rotation lock.
     *
     * Should be used by the rotation lock toggle.
     */
    public static void setRotationLock(Context context, final boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, 0,
                UserHandle.USER_CURRENT);

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    if (enabled) {
                        wm.freezeRotation(-1);
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
     * Enables or disables rotation lock and adjusts whether the rotation lock toggle
     * should be hidden for accessibility purposes.
     *
     * Should be used by Display settings and Accessibility settings.
     */
    public static void setRotationLockForAccessibility(Context context, final boolean enabled) {
        Settings.System.putIntForUser(context.getContentResolver(),
                Settings.System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, enabled ? 1 : 0,
                        UserHandle.USER_CURRENT);

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
                    if (enabled) {
                        wm.freezeRotation(Surface.ROTATION_0);
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
            public void onChange(boolean selfChange, Uri uri) {
                RotationPolicyListener.this.onChange();
            }
        };

        public abstract void onChange();
    }
}
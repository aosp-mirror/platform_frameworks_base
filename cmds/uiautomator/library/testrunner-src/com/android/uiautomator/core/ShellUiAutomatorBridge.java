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

package com.android.uiautomator.core;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IActivityManager.ContentProviderHolder;
import android.app.UiAutomation;
import android.content.Context;
import android.content.IContentProvider;
import android.database.Cursor;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;

/**
 * @hide
 */
public class ShellUiAutomatorBridge extends UiAutomatorBridge {

    private static final String LOG_TAG = ShellUiAutomatorBridge.class.getSimpleName();

    public ShellUiAutomatorBridge(UiAutomation uiAutomation) {
        super(uiAutomation);
    }

    public Display getDefaultDisplay() {
        return DisplayManagerGlobal.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY);
    }

    public long getSystemLongPressTime() {
        // Read the long press timeout setting.
        long longPressTimeout = 0;
        try {
            IContentProvider provider = null;
            Cursor cursor = null;
            IActivityManager activityManager = ActivityManagerNative.getDefault();
            String providerName = Settings.Secure.CONTENT_URI.getAuthority();
            IBinder token = new Binder();
            try {
                ContentProviderHolder holder = activityManager.getContentProviderExternal(
                        providerName, UserHandle.USER_SYSTEM, token);
                if (holder == null) {
                    throw new IllegalStateException("Could not find provider: " + providerName);
                }
                provider = holder.provider;
                cursor = provider.query(null, Settings.Secure.CONTENT_URI,
                        new String[] {
                            Settings.Secure.VALUE
                        }, "name=?",
                        new String[] {
                            Settings.Secure.LONG_PRESS_TIMEOUT
                        }, null, null);
                if (cursor.moveToFirst()) {
                    longPressTimeout = cursor.getInt(0);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
                if (provider != null) {
                    activityManager.removeContentProviderExternal(providerName, token);
                }
            }
        } catch (RemoteException e) {
            String message = "Error reading long press timeout setting.";
            Log.e(LOG_TAG, message, e);
            throw new RuntimeException(message, e);
        }
        return longPressTimeout;
    }

    @Override
    public int getRotation() {
        IWindowManager wm =
                IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        int ret = -1;
        try {
            ret = wm.getRotation();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error getting screen rotation", e);
            throw new RuntimeException(e);
        }
        return ret;
    }

    @Override
    public boolean isScreenOn() {
        IPowerManager pm =
                IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
        boolean ret = false;
        try {
            ret = pm.isInteractive();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error getting screen status", e);
            throw new RuntimeException(e);
        }
        return ret;
    }
}

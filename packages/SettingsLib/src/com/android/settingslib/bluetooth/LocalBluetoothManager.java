/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

import java.lang.ref.WeakReference;

/**
 * LocalBluetoothManager provides a simplified interface on top of a subset of
 * the Bluetooth API. Note that {@link #getInstance} will return null
 * if there is no Bluetooth adapter on this device, and callers must be
 * prepared to handle this case.
 */
public class LocalBluetoothManager {
    private static final String TAG = "LocalBluetoothManager";

    /** Singleton instance. */
    private static LocalBluetoothManager sInstance;

    private final Context mContext;

    /** If a BT-related activity is in the foreground, this will be it. */
    private WeakReference<Context> mForegroundActivity;

    private final LocalBluetoothAdapter mLocalAdapter;

    private final CachedBluetoothDeviceManager mCachedDeviceManager;

    /** The Bluetooth profile manager. */
    private final LocalBluetoothProfileManager mProfileManager;

    /** The broadcast receiver event manager. */
    private final BluetoothEventManager mEventManager;

    @Nullable
    public static synchronized LocalBluetoothManager getInstance(Context context,
            BluetoothManagerCallback onInitCallback) {
        if (sInstance == null) {
            LocalBluetoothAdapter adapter = LocalBluetoothAdapter.getInstance();
            if (adapter == null) {
                return null;
            }
            // This will be around as long as this process is
            sInstance = new LocalBluetoothManager(adapter, context, /* handler= */ null,
                    /* userHandle= */ null);
            if (onInitCallback != null) {
                onInitCallback.onBluetoothManagerInitialized(context.getApplicationContext(),
                        sInstance);
            }
        }

        return sInstance;
    }

    /**
     * Returns a new instance of {@link LocalBluetoothManager} or null if Bluetooth is not
     * supported for this hardware. This instance should be globally cached by the caller.
     */
    @Nullable
    public static LocalBluetoothManager create(Context context, Handler handler) {
        LocalBluetoothAdapter adapter = LocalBluetoothAdapter.getInstance();
        if (adapter == null) {
            return null;
        }
        return new LocalBluetoothManager(adapter, context, handler, /* userHandle= */ null);
    }

    /**
     * Returns a new instance of {@link LocalBluetoothManager} or null if Bluetooth is not
     * supported for this hardware. This instance should be globally cached by the caller.
     *
     * <p> Allows to specify a {@link UserHandle} for which to receive bluetooth events.
     *
     * <p> Requires {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public static LocalBluetoothManager create(Context context, Handler handler,
            UserHandle userHandle) {
        LocalBluetoothAdapter adapter = LocalBluetoothAdapter.getInstance();
        if (adapter == null) {
            return null;
        }
        return new LocalBluetoothManager(adapter, context, handler,
                userHandle);
    }

    private LocalBluetoothManager(LocalBluetoothAdapter adapter, Context context, Handler handler,
            UserHandle userHandle) {
        mContext = context.getApplicationContext();
        mLocalAdapter = adapter;
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, this);
        mEventManager =
                new BluetoothEventManager(
                        mLocalAdapter,
                        this,
                        mCachedDeviceManager,
                        mContext,
                        handler,
                        userHandle);
        mProfileManager =
                new LocalBluetoothProfileManager(
                        mContext, mLocalAdapter, mCachedDeviceManager, mEventManager);

        mProfileManager.updateLocalProfiles();
        mEventManager.readPairedDevices();
    }

    public LocalBluetoothAdapter getBluetoothAdapter() {
        return mLocalAdapter;
    }

    public Context getContext() {
        return mContext;
    }

    public Context getForegroundActivity() {
        return mForegroundActivity == null
                ? null
                : mForegroundActivity.get();
    }

    public boolean isForegroundActivity() {
        return mForegroundActivity != null && mForegroundActivity.get() != null;
    }

    public synchronized void setForegroundActivity(Context context) {
        if (context != null) {
            Log.d(TAG, "setting foreground activity to non-null context");
            mForegroundActivity = new WeakReference<>(context);
        } else {
            if (mForegroundActivity != null) {
                Log.d(TAG, "setting foreground activity to null");
                mForegroundActivity = null;
            }
        }
    }

    public CachedBluetoothDeviceManager getCachedDeviceManager() {
        return mCachedDeviceManager;
    }

    public BluetoothEventManager getEventManager() {
        return mEventManager;
    }

    public LocalBluetoothProfileManager getProfileManager() {
        return mProfileManager;
    }

    public interface BluetoothManagerCallback {
        void onBluetoothManagerInitialized(Context appContext,
                LocalBluetoothManager bluetoothManager);
    }
}

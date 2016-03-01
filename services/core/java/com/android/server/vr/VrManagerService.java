/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.vr;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.ArrayList;

/**
 * Service tracking whether VR mode is active, and notifying listening system services of state
 * changes.
 *
 * {@hide}
 */
public class VrManagerService extends SystemService {

    public static final String TAG = "VrManagerService";

    private static native void initializeNative();
    private static native void setVrModeNative(boolean enabled);

    private final Object mLock = new Object();

    private final IBinder mOverlayToken = new Binder();

    private boolean mVrModeEnabled = false;
    private ArraySet<VrStateListener> mListeners = new ArraySet<>();

    private final class LocalService extends VrManagerInternal {
        @Override
        public boolean isInVrMode() {
            return VrManagerService.this.getVrMode();
        }

        @Override
        public void setVrMode(boolean enabled) {
            VrManagerService.this.setVrMode(enabled);
        }

        @Override
        public void registerListener(VrStateListener listener) {
            VrManagerService.this.addListener(listener);
        }

        @Override
        public void unregisterListener(VrStateListener listener) {
            VrManagerService.this.removeListener(listener);
        }
    }

    public VrManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        synchronized(mLock) {
            initializeNative();
        }

        publishLocalService(VrManagerInternal.class, new LocalService());
    }

    private void addListener(VrStateListener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    private void removeListener(VrStateListener listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    private void setVrMode(boolean enabled) {
        synchronized (mLock) {
            if (mVrModeEnabled != enabled) {
                mVrModeEnabled = enabled;
                // Log mode change event.
                Slog.i(TAG, "VR mode " + ((mVrModeEnabled) ? "enabled" : "disabled"));
                setVrModeNative(mVrModeEnabled);
                updateOverlayStateLocked();
                onVrModeChangedLocked();
            }
        }
    }

    private void updateOverlayStateLocked() {
        final long identity = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            if (appOpsManager != null) {
                appOpsManager.setUserRestriction(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                        mVrModeEnabled, mOverlayToken);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean getVrMode() {
        synchronized (mLock) {
            return mVrModeEnabled;
        }
    }

    /**
     * Notify system services of VR mode change.
     */
    private void onVrModeChangedLocked() {
        for (VrStateListener l : mListeners) {
            l.onVrStateChanged(mVrModeEnabled);
        }
    }
}

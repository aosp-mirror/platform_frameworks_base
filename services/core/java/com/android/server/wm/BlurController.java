/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.ICrossWindowBlurEnabledListener;
import android.view.TunnelModeEnabledListener;

/**
 * Keeps track of the different factors that determine whether cross-window blur is enabled
 * or disabled. Also keeps a list of all interested listeners and notifies them when the
 * blur enabled state changes.
 */
final class BlurController {
    private final Context mContext;
    private final RemoteCallbackList<ICrossWindowBlurEnabledListener>
            mBlurEnabledListeners = new RemoteCallbackList<>();
    // We don't use the WM global lock, because the BlurController is not involved in window
    // drawing and only receives binder calls that don't need synchronization with the rest of WM
    private final Object mLock = new Object();
    private volatile boolean mBlurEnabled;
    private boolean mInPowerSaveMode;
    private boolean mBlurDisabledSetting;
    private boolean mTunnelModeEnabled = false;

    private TunnelModeEnabledListener mTunnelModeListener =
            new TunnelModeEnabledListener(Runnable::run) {
        @Override
        public void onTunnelModeEnabledChanged(boolean tunnelModeEnabled) {
            mTunnelModeEnabled = tunnelModeEnabled;
            updateBlurEnabled();
        }
    };

    BlurController(Context context, PowerManager powerManager) {
        mContext = context;

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                    // onReceive always gets called on the same thread, so there is no
                    // multi-threaded execution here. Thus, we don't have to hold mLock here.
                    mInPowerSaveMode = powerManager.isPowerSaveMode();
                    updateBlurEnabled();
                }
            }
        }, filter, null, null);
        mInPowerSaveMode = powerManager.isPowerSaveMode();

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DISABLE_WINDOW_BLURS), false,
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        super.onChange(selfChange);
                        // onChange always gets called on the same thread, so there is no
                        // multi-threaded execution here. Thus, we don't have to hold mLock here.
                        mBlurDisabledSetting = getBlurDisabledSetting();
                        updateBlurEnabled();
                    }
                });
        mBlurDisabledSetting = getBlurDisabledSetting();

        TunnelModeEnabledListener.register(mTunnelModeListener);

        updateBlurEnabled();
    }

    boolean registerCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return false;
        mBlurEnabledListeners.register(listener);
        return getBlurEnabled();
    }

    void unregisterCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return;
        mBlurEnabledListeners.unregister(listener);
    }

    boolean getBlurEnabled() {
        return mBlurEnabled;
    }

    private void updateBlurEnabled() {
        synchronized (mLock) {
            final boolean newEnabled = CROSS_WINDOW_BLUR_SUPPORTED && !mBlurDisabledSetting
                    && !mInPowerSaveMode && !mTunnelModeEnabled;
            if (mBlurEnabled == newEnabled) {
                return;
            }
            mBlurEnabled = newEnabled;
            notifyBlurEnabledChangedLocked(newEnabled);
        }
    }

    private void notifyBlurEnabledChangedLocked(boolean enabled) {
        int i = mBlurEnabledListeners.beginBroadcast();
        while (i > 0) {
            i--;
            ICrossWindowBlurEnabledListener listener =
                    mBlurEnabledListeners.getBroadcastItem(i);
            try {
                listener.onCrossWindowBlurEnabledChanged(enabled);
            } catch (RemoteException e) {
            }
        }
        mBlurEnabledListeners.finishBroadcast();
    }

    private boolean getBlurDisabledSetting() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DISABLE_WINDOW_BLURS, 0) == 1;
    }
}

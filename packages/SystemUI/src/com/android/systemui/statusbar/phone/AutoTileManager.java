/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.NightDisplayController;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotController.Callback;

/**
 * Manages which tiles should be automatically added to QS.
 */
public class AutoTileManager {

    private final Context mContext;
    private final QSTileHost mHost;
    private final Handler mHandler;

    public AutoTileManager(Context context, QSTileHost host) {
        mContext = context;
        mHost = host;
        mHandler = new Handler((Looper) Dependency.get(Dependency.BG_LOOPER));
        if (!Prefs.getBoolean(context, Key.QS_HOTSPOT_ADDED, false)) {
            Dependency.get(HotspotController.class).addCallback(mHotspotCallback);
        }
        if (!Prefs.getBoolean(context, Key.QS_DATA_SAVER_ADDED, false)) {
            Dependency.get(DataSaverController.class).addCallback(mDataSaverListener);
        }
        if (!Prefs.getBoolean(context, Key.QS_INVERT_COLORS_ADDED, false)) {
            mColorsSetting = new SecureSetting(mContext, mHandler,
                    Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
                @Override
                protected void handleValueChanged(int value, boolean observedChange) {
                    if (value != 0) {
                        mHost.addTile("inversion");
                        Prefs.putBoolean(mContext, Key.QS_INVERT_COLORS_ADDED, true);
                        mHandler.post(() -> mColorsSetting.setListening(false));
                    }
                }
            };
            mColorsSetting.setListening(true);
        }
        if (!Prefs.getBoolean(context, Key.QS_WORK_ADDED, false)) {
            Dependency.get(ManagedProfileController.class).addCallback(mProfileCallback);
        }

        if (!Prefs.getBoolean(context, Key.QS_NIGHTDISPLAY_ADDED, false)
                && NightDisplayController.isAvailable(mContext)) {
            Dependency.get(NightDisplayController.class).setListener(mNightDisplayCallback);
        }
    }

    public void destroy() {
        mColorsSetting.setListening(false);
        Dependency.get(HotspotController.class).removeCallback(mHotspotCallback);
        Dependency.get(DataSaverController.class).removeCallback(mDataSaverListener);
        Dependency.get(ManagedProfileController.class).removeCallback(mProfileCallback);
        Dependency.get(NightDisplayController.class).setListener(null);
    }

    private final ManagedProfileController.Callback mProfileCallback =
            new ManagedProfileController.Callback() {
                @Override
                public void onManagedProfileChanged() {
                    if (Dependency.get(ManagedProfileController.class).hasActiveProfile()) {
                        mHost.addTile("work");
                        Prefs.putBoolean(mContext, Key.QS_WORK_ADDED, true);
                        mHandler.post(() -> Dependency.get(ManagedProfileController.class)
                                .removeCallback(mProfileCallback));
                    }
                }

                @Override
                public void onManagedProfileRemoved() {
                }
            };

    private SecureSetting mColorsSetting;

    private final DataSaverController.Listener mDataSaverListener = new Listener() {
        @Override
        public void onDataSaverChanged(boolean isDataSaving) {
            if (isDataSaving) {
                mHost.addTile("saver");
                Prefs.putBoolean(mContext, Key.QS_DATA_SAVER_ADDED, true);
                mHandler.post(() -> Dependency.get(DataSaverController.class).removeCallback(
                        mDataSaverListener));
            }
        }
    };

    private final HotspotController.Callback mHotspotCallback = new Callback() {
        @Override
        public void onHotspotChanged(boolean enabled) {
            if (enabled) {
                mHost.addTile("hotspot");
                Prefs.putBoolean(mContext, Key.QS_HOTSPOT_ADDED, true);
                mHandler.post(() -> Dependency.get(HotspotController.class)
                        .removeCallback(mHotspotCallback));
            }
        }
    };

    @VisibleForTesting
    final NightDisplayController.Callback mNightDisplayCallback =
            new NightDisplayController.Callback() {
        @Override
        public void onActivated(boolean activated) {
            if (activated) {
                addNightTile();
            }
        }

        @Override
        public void onAutoModeChanged(int autoMode) {
            if (autoMode == NightDisplayController.AUTO_MODE_CUSTOM
                    || autoMode == NightDisplayController.AUTO_MODE_TWILIGHT) {
                addNightTile();
            }
        }

        private void addNightTile() {
            mHost.addTile("night");
            Prefs.putBoolean(mContext, Key.QS_NIGHTDISPLAY_ADDED, true);
            mHandler.post(() -> Dependency.get(NightDisplayController.class)
                    .setListener(null));
        }
    };
}

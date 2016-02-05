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
import android.provider.Settings.Secure;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
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
        mHandler = new Handler(mHost.getLooper());
        if (!Prefs.getBoolean(context, Key.QS_HOTSPOT_ADDED, false)) {
            host.getHotspotController().addCallback(mHotspotCallback);
        }
        if (!Prefs.getBoolean(context, Key.QS_DATA_SAVER_ADDED, false)) {
            host.getNetworkController().getDataSaverController().addListener(mDataSaverListener);
        }
        if (!Prefs.getBoolean(context, Key.QS_INVERT_COLORS_ADDED, false)) {
            mColorsSetting = new SecureSetting(mContext, mHandler,
                    Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED) {
                @Override
                protected void handleValueChanged(int value, boolean observedChange) {
                    if (value != 0) {
                        mHost.addTile("inversion");
                        Prefs.putBoolean(mContext, Key.QS_INVERT_COLORS_ADDED, true);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mColorsSetting.setListening(false);
                            }
                        });
                    }
                }
            };
            mColorsSetting.setListening(true);
        }
        if (!Prefs.getBoolean(context, Key.QS_WORK_ADDED, false)) {
            host.getManagedProfileController().addCallback(mProfileCallback);
        }
    }

    public void destroy() {
        // TODO: Remove any registered listeners.
    }

    private final ManagedProfileController.Callback mProfileCallback =
            new ManagedProfileController.Callback() {
                @Override
                public void onManagedProfileChanged() {
                    if (mHost.getManagedProfileController().hasActiveProfile()) {
                        mHost.addTile("work");
                        Prefs.putBoolean(mContext, Key.QS_WORK_ADDED, true);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mHost.getManagedProfileController().removeCallback(
                                        mProfileCallback);
                            }
                        });
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
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mHost.getNetworkController().getDataSaverController().remListener(
                                mDataSaverListener);
                    }
                });
            }
        }
    };

    private final HotspotController.Callback mHotspotCallback = new Callback() {
        @Override
        public void onHotspotChanged(boolean enabled) {
            if (enabled) {
                mHost.addTile("hotspot");
                Prefs.putBoolean(mContext, Key.QS_HOTSPOT_ADDED, true);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mHost.getHotspotController().removeCallback(mHotspotCallback);
                    }
                });
            }
        }
    };
}

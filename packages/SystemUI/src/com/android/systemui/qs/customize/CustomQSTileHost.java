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
package com.android.systemui.qs.customize;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.SecurityController;

import java.util.ArrayList;
import java.util.List;

/**
 * @see CustomQSPanel
 */
public class CustomQSTileHost extends QSTileHost {

    private static final String TAG = "CustomHost";
    private List<String> mTiles;
    private List<String> mSavedTiles;

    public CustomQSTileHost(Context context, QSTileHost host) {
        super(context, null, host.getBluetoothController(), host.getLocationController(),
                host.getRotationLockController(), host.getNetworkController(),
                host.getZenModeController(), host.getHotspotController(), host.getCastController(),
                host.getFlashlightController(), host.getUserSwitcherController(),
                host.getKeyguardMonitor(), new BlankSecurityController());
    }

    @Override
    protected QSTile<?> createTile(String tileSpec) {
        QSTile<?> tile = super.createTile(tileSpec);
        tile.setTileSpec(tileSpec);
        return tile;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        // No Tunings For You.
        if (TILES_SETTING.equals(key)) {
            mSavedTiles = super.loadTileSpecs(newValue);
        }
    }

    public void setSavedTiles() {
        setTiles(mSavedTiles);
    }

    public void saveCurrentTiles() {
        Secure.putStringForUser(getContext().getContentResolver(), TILES_SETTING,
                TextUtils.join(",", mTiles), ActivityManager.getCurrentUser());
    }

    public void moveTo(String from, String to) {
        int fromIndex = mTiles.indexOf(from);
        if (fromIndex < 0) {
            Log.e(TAG, "Unknown from tile " + from);
            return;
        }
        int index = mTiles.indexOf(to);
        if (index < 0) {
            Log.e(TAG, "Unknown to tile " + to);
            return;
        }
        mTiles.remove(fromIndex);
        mTiles.add(index, from);
        super.onTuningChanged(TILES_SETTING, null);
    }

    public void setTiles(List<String> tiles) {
        mTiles = new ArrayList<>(tiles);
        super.onTuningChanged(TILES_SETTING, null);
    }

    @Override
    protected List<String> loadTileSpecs(String tileList) {
        return mTiles;
    }

    public void replace(String oldTile, String newTile) {
        if (oldTile.equals(newTile)) {
            return;
        }
        MetricsLogger.action(getContext(), MetricsLogger.TUNER_QS_REORDER, oldTile + ","
                + newTile);
        List<String> order = new ArrayList<>(mTileSpecs);
        int index = order.indexOf(oldTile);
        if (index < 0) {
            Log.e(TAG, "Can't find " + oldTile);
            return;
        }
        order.remove(newTile);
        order.add(index, newTile);
        setTiles(order);
    }

    /**
     * Blank so that the customizing QS view doesn't show any security messages in the footer.
     */
    private static class BlankSecurityController implements SecurityController {
        @Override
        public boolean hasDeviceOwner() {
            return false;
        }

        @Override
        public boolean hasProfileOwner() {
            return false;
        }

        @Override
        public String getDeviceOwnerName() {
            return null;
        }

        @Override
        public String getProfileOwnerName() {
            return null;
        }

        @Override
        public boolean isVpnEnabled() {
            return false;
        }

        @Override
        public String getPrimaryVpnName() {
            return null;
        }

        @Override
        public String getProfileVpnName() {
            return null;
        }

        @Override
        public void onUserSwitched(int newUserId) {
        }

        @Override
        public void addCallback(SecurityControllerCallback callback) {
        }

        @Override
        public void removeCallback(SecurityControllerCallback callback) {
        }
    }
}

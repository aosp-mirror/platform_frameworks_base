/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.Looper;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.tiles.AirplaneModeTile;
import com.android.systemui.qs.tiles.BluetoothTile;
import com.android.systemui.qs.tiles.BugreportTile;
import com.android.systemui.qs.tiles.CastTile;
import com.android.systemui.qs.tiles.CellularTile;
import com.android.systemui.qs.tiles.ColorInversionTile;
import com.android.systemui.qs.tiles.LocationTile;
import com.android.systemui.qs.tiles.NotificationsTile;
import com.android.systemui.qs.tiles.RotationLockTile;
import com.android.systemui.qs.tiles.HotspotTile;
import com.android.systemui.qs.tiles.WifiTile;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.TetheringController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeComponent;

import java.util.ArrayList;
import java.util.List;

/** Platform implementation of the quick settings tile host **/
public class QSTileHost implements QSTile.Host {

    private final Context mContext;
    private final PhoneStatusBar mStatusBar;
    private final BluetoothController mBluetooth;
    private final LocationController mLocation;
    private final RotationLockController mRotation;
    private final NetworkController mNetwork;
    private final ZenModeController mZen;
    private final TetheringController mTethering;
    private final CastController mCast;
    private final Looper mLooper;
    private final CurrentUserTracker mUserTracker;
    private final VolumeComponent mVolume;
    private final ArrayList<QSTile<?>> mTiles = new ArrayList<QSTile<?>>();
    private final int mFeedbackStartDelay;

    public QSTileHost(Context context, PhoneStatusBar statusBar,
            BluetoothController bluetooth, LocationController location,
            RotationLockController rotation, NetworkController network,
            ZenModeController zen, TetheringController tethering,
            CastController cast, VolumeComponent volume) {
        mContext = context;
        mStatusBar = statusBar;
        mBluetooth = bluetooth;
        mLocation = location;
        mRotation = rotation;
        mNetwork = network;
        mZen = zen;
        mTethering = tethering;
        mCast = cast;
        mVolume = volume;

        final HandlerThread ht = new HandlerThread(QSTileHost.class.getSimpleName());
        ht.start();
        mLooper = ht.getLooper();

        mTiles.add(new WifiTile(this));
        mTiles.add(new BluetoothTile(this));
        mTiles.add(new ColorInversionTile(this));
        mTiles.add(new CellularTile(this));
        mTiles.add(new AirplaneModeTile(this));
        mTiles.add(new NotificationsTile(this));
        mTiles.add(new RotationLockTile(this));
        mTiles.add(new LocationTile(this));
        mTiles.add(new CastTile(this));
        mTiles.add(new HotspotTile(this));
        mTiles.add(new BugreportTile(this));

        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                for (QSTile<?> tile : mTiles) {
                    tile.userSwitch(newUserId);
                }
            }
        };
        mUserTracker.startTracking();
        mFeedbackStartDelay = mContext.getResources().getInteger(R.integer.feedback_start_delay);
    }

    @Override
    public List<QSTile<?>> getTiles() {
        return mTiles;
    }

    @Override
    public void startSettingsActivity(final Intent intent) {
        mStatusBar.postStartSettingsActivity(intent, mFeedbackStartDelay);
    }

    @Override
    public void warn(String message, Throwable t) {
        // already logged
    }

    @Override
    public void collapsePanels() {
        mStatusBar.postAnimateCollapsePanels();
    }

    @Override
    public Looper getLooper() {
        return mLooper;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public BluetoothController getBluetoothController() {
        return mBluetooth;
    }

    @Override
    public LocationController getLocationController() {
        return mLocation;
    }

    @Override
    public RotationLockController getRotationLockController() {
        return mRotation;
    }

    @Override
    public NetworkController getNetworkController() {
        return mNetwork;
    }

    @Override
    public ZenModeController getZenModeController() {
        return mZen;
    }

    @Override
    public TetheringController getTetheringController() {
        return mTethering;
    }

    @Override
    public CastController getCastController() {
        return mCast;
    }

    @Override
    public VolumeComponent getVolumeComponent() {
        return mVolume;
    }
}

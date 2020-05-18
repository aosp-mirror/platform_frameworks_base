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
import android.content.res.Resources;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotController.Callback;
import com.android.systemui.util.UserAwareController;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Manages which tiles should be automatically added to QS.
 */
public class AutoTileManager implements UserAwareController {
    private static final String TAG = "AutoTileManager";

    public static final String HOTSPOT = "hotspot";
    public static final String SAVER = "saver";
    public static final String INVERSION = "inversion";
    public static final String WORK = "work";
    public static final String NIGHT = "night";
    public static final String CAST = "cast";
    static final String SETTING_SEPARATOR = ":";

    private UserHandle mCurrentUser;
    private boolean mInitialized;

    protected final Context mContext;
    protected final QSTileHost mHost;
    protected final Handler mHandler;
    protected final AutoAddTracker mAutoTracker;
    private final HotspotController mHotspotController;
    private final DataSaverController mDataSaverController;
    private final ManagedProfileController mManagedProfileController;
    private final NightDisplayListener mNightDisplayListener;
    private final CastController mCastController;
    private final ArrayList<AutoAddSetting> mAutoAddSettingList = new ArrayList<>();

    public AutoTileManager(Context context, AutoAddTracker.Builder autoAddTrackerBuilder,
            QSTileHost host,
            @Background Handler handler,
            HotspotController hotspotController,
            DataSaverController dataSaverController,
            ManagedProfileController managedProfileController,
            NightDisplayListener nightDisplayListener,
            CastController castController) {
        mContext = context;
        mHost = host;
        mCurrentUser = mHost.getUserContext().getUser();
        mAutoTracker = autoAddTrackerBuilder.setUserId(mCurrentUser.getIdentifier()).build();
        mHandler = handler;
        mHotspotController = hotspotController;
        mDataSaverController = dataSaverController;
        mManagedProfileController = managedProfileController;
        mNightDisplayListener = nightDisplayListener;
        mCastController = castController;
    }

    /**
     * Init method must be called after construction to start listening
     */
    public void init() {
        if (mInitialized) {
            Log.w(TAG, "Trying to re-initialize");
            return;
        }
        mAutoTracker.initialize();
        populateSettingsList();
        startControllersAndSettingsListeners();
        mInitialized = true;
    }

    protected void startControllersAndSettingsListeners() {
        if (!mAutoTracker.isAdded(HOTSPOT)) {
            mHotspotController.addCallback(mHotspotCallback);
        }
        if (!mAutoTracker.isAdded(SAVER)) {
            mDataSaverController.addCallback(mDataSaverListener);
        }
        if (!mAutoTracker.isAdded(WORK)) {
            mManagedProfileController.addCallback(mProfileCallback);
        }
        if (!mAutoTracker.isAdded(NIGHT)
                && ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            mNightDisplayListener.setCallback(mNightDisplayCallback);
        }
        if (!mAutoTracker.isAdded(CAST)) {
            mCastController.addCallback(mCastCallback);
        }

        int settingsN = mAutoAddSettingList.size();
        for (int i = 0; i < settingsN; i++) {
            if (!mAutoTracker.isAdded(mAutoAddSettingList.get(i).mSpec)) {
                mAutoAddSettingList.get(i).setListening(true);
            }
        }
    }

    protected void stopListening() {
        mHotspotController.removeCallback(mHotspotCallback);
        mDataSaverController.removeCallback(mDataSaverListener);
        mManagedProfileController.removeCallback(mProfileCallback);
        if (ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            mNightDisplayListener.setCallback(null);
        }
        mCastController.removeCallback(mCastCallback);
        int settingsN = mAutoAddSettingList.size();
        for (int i = 0; i < settingsN; i++) {
            mAutoAddSettingList.get(i).setListening(false);
        }
    }

    public void destroy() {
        stopListening();
        mAutoTracker.destroy();
    }

    /**
     * Populates a list with the pairs setting:spec in the config resource.
     * <p>
     * This will only create {@link AutoAddSetting} objects for those tiles that have not been
     * auto-added before, and set the corresponding {@link ContentObserver} to listening.
     */
    private void populateSettingsList() {
        String [] autoAddList;
        try {
            autoAddList = mContext.getResources().getStringArray(
                    R.array.config_quickSettingsAutoAdd);
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Missing config resource");
            return;
        }
        // getStringArray returns @NotNull, so if we got here, autoAddList is not null
        for (String tile : autoAddList) {
            String[] split = tile.split(SETTING_SEPARATOR);
            if (split.length == 2) {
                String setting = split[0];
                String spec = split[1];
                // Populate all the settings. As they may not have been added in other users
                AutoAddSetting s = new AutoAddSetting(mContext, mHandler, setting, spec);
                mAutoAddSettingList.add(s);
            } else {
                Log.w(TAG, "Malformed item in array: " + tile);
            }
        }
    }

    /*
     * This will be sent off the main thread if needed
     */
    @Override
    public void changeUser(UserHandle newUser) {
        if (!mInitialized) {
            throw new IllegalStateException("AutoTileManager not initialized");
        }
        if (!Thread.currentThread().equals(mHandler.getLooper().getThread())) {
            mHandler.post(() -> changeUser(newUser));
            return;
        }
        if (newUser.getIdentifier() == mCurrentUser.getIdentifier()) {
            return;
        }
        stopListening();
        mCurrentUser = newUser;
        int settingsN = mAutoAddSettingList.size();
        for (int i = 0; i < settingsN; i++) {
            mAutoAddSettingList.get(i).setUserId(newUser.getIdentifier());
        }
        mAutoTracker.changeUser(newUser);
        startControllersAndSettingsListeners();
    }

    @Override
    public int getCurrentUserId() {
        return mCurrentUser.getIdentifier();
    }

    public void unmarkTileAsAutoAdded(String tabSpec) {
        mAutoTracker.setTileRemoved(tabSpec);
    }

    private final ManagedProfileController.Callback mProfileCallback =
            new ManagedProfileController.Callback() {
                @Override
                public void onManagedProfileChanged() {
                    if (mAutoTracker.isAdded(WORK)) return;
                    if (mManagedProfileController.hasActiveProfile()) {
                        mHost.addTile(WORK);
                        mAutoTracker.setTileAdded(WORK);
                    }
                }

                @Override
                public void onManagedProfileRemoved() {
                }
            };

    private final DataSaverController.Listener mDataSaverListener = new Listener() {
        @Override
        public void onDataSaverChanged(boolean isDataSaving) {
            if (mAutoTracker.isAdded(SAVER)) return;
            if (isDataSaving) {
                mHost.addTile(SAVER);
                mAutoTracker.setTileAdded(SAVER);
                mHandler.post(() -> mDataSaverController.removeCallback(mDataSaverListener));
            }
        }
    };

    private final HotspotController.Callback mHotspotCallback = new Callback() {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            if (mAutoTracker.isAdded(HOTSPOT)) return;
            if (enabled) {
                mHost.addTile(HOTSPOT);
                mAutoTracker.setTileAdded(HOTSPOT);
                mHandler.post(() -> mHotspotController.removeCallback(mHotspotCallback));
            }
        }
    };

    @VisibleForTesting
    final NightDisplayListener.Callback mNightDisplayCallback =
            new NightDisplayListener.Callback() {
        @Override
        public void onActivated(boolean activated) {
            if (activated) {
                addNightTile();
            }
        }

        @Override
        public void onAutoModeChanged(int autoMode) {
            if (autoMode == ColorDisplayManager.AUTO_MODE_CUSTOM_TIME
                    || autoMode == ColorDisplayManager.AUTO_MODE_TWILIGHT) {
                addNightTile();
            }
        }

        private void addNightTile() {
            if (mAutoTracker.isAdded(NIGHT)) return;
            mHost.addTile(NIGHT);
            mAutoTracker.setTileAdded(NIGHT);
            mHandler.post(() -> mNightDisplayListener.setCallback(null));
        }
    };

    @VisibleForTesting
    final CastController.Callback mCastCallback = new CastController.Callback() {
        @Override
        public void onCastDevicesChanged() {
            if (mAutoTracker.isAdded(CAST)) return;

            boolean isCasting = false;
            for (CastDevice device : mCastController.getCastDevices()) {
                if (device.state == CastDevice.STATE_CONNECTED
                        || device.state == CastDevice.STATE_CONNECTING) {
                    isCasting = true;
                    break;
                }
            }

            if (isCasting) {
                mHost.addTile(CAST);
                mAutoTracker.setTileAdded(CAST);
                mHandler.post(() -> mCastController.removeCallback(mCastCallback));
            }
        }
    };

    @VisibleForTesting
    protected SecureSetting getSecureSettingForKey(String key) {
        for (SecureSetting s : mAutoAddSettingList) {
            if (Objects.equals(key, s.getKey())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Tracks tiles that should be auto added when a setting changes.
     * <p>
     * When the setting changes to a value different from 0, if the tile has not been auto added
     * before, it will be added and the listener will be stopped.
     */
    private class AutoAddSetting extends SecureSetting {
        private final String mSpec;

        AutoAddSetting(Context context, Handler handler, String setting, String tileSpec) {
            super(context, handler, setting);
            mSpec = tileSpec;
        }

        @Override
        protected void handleValueChanged(int value, boolean observedChange) {
            if (mAutoTracker.isAdded(mSpec)) {
                // This should not be listening anymore
                mHandler.post(() -> setListening(false));
                return;
            }
            if (value != 0) {
                if (mSpec.startsWith(CustomTile.PREFIX)) {
                    mHost.addTile(CustomTile.getComponentFromSpec(mSpec));
                } else {
                    mHost.addTile(mSpec);
                }
                mAutoTracker.setTileAdded(mSpec);
                mHandler.post(() -> setListening(false));
            }
        }
    }
}

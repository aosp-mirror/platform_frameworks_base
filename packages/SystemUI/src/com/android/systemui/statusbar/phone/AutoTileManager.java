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

import static com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.NightDisplayListenerModule;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.SettingObserver;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.DeviceControlsController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotController.Callback;
import com.android.systemui.statusbar.policy.SafetyController;
import com.android.systemui.statusbar.policy.WalletController;
import com.android.systemui.util.UserAwareController;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

import javax.inject.Named;

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
    public static final String DEVICE_CONTROLS = "controls";
    public static final String WALLET = "wallet";
    public static final String BRIGHTNESS = "reduce_brightness";
    static final String SETTING_SEPARATOR = ":";

    private UserHandle mCurrentUser;
    private boolean mInitialized;
    private final String mSafetySpec;

    protected final Context mContext;
    protected final QSHost mHost;
    protected final Handler mHandler;
    protected final SecureSettings mSecureSettings;
    protected final AutoAddTracker mAutoTracker;
    private final HotspotController mHotspotController;
    private final DataSaverController mDataSaverController;
    private final ManagedProfileController mManagedProfileController;
    private final NightDisplayListenerModule.Builder mNightDisplayListenerBuilder;
    private NightDisplayListener mNightDisplayListener;
    private final CastController mCastController;
    private final DeviceControlsController mDeviceControlsController;
    private final WalletController mWalletController;
    private final ReduceBrightColorsController mReduceBrightColorsController;
    private final SafetyController mSafetyController;
    private final boolean mIsReduceBrightColorsAvailable;
    private final ArrayList<AutoAddSetting> mAutoAddSettingList = new ArrayList<>();

    public AutoTileManager(Context context, AutoAddTracker.Builder autoAddTrackerBuilder,
            QSHost host,
            @Background Handler handler,
            SecureSettings secureSettings,
            HotspotController hotspotController,
            DataSaverController dataSaverController,
            ManagedProfileController managedProfileController,
            NightDisplayListenerModule.Builder nightDisplayListenerBuilder,
            CastController castController,
            ReduceBrightColorsController reduceBrightColorsController,
            DeviceControlsController deviceControlsController,
            WalletController walletController,
            SafetyController safetyController,
            @Named(RBC_AVAILABLE) boolean isReduceBrightColorsAvailable) {
        mContext = context;
        mHost = host;
        mSecureSettings = secureSettings;
        mCurrentUser = mHost.getUserContext().getUser();
        mAutoTracker = autoAddTrackerBuilder.setUserId(mCurrentUser.getIdentifier()).build();
        mHandler = handler;
        mHotspotController = hotspotController;
        mDataSaverController = dataSaverController;
        mManagedProfileController = managedProfileController;
        mNightDisplayListenerBuilder = nightDisplayListenerBuilder;
        mCastController = castController;
        mReduceBrightColorsController = reduceBrightColorsController;
        mIsReduceBrightColorsAvailable = isReduceBrightColorsAvailable;
        mDeviceControlsController = deviceControlsController;
        mWalletController = walletController;
        mSafetyController = safetyController;
        String safetySpecClass;
        try {
            safetySpecClass =
                    context.getResources().getString(R.string.safety_quick_settings_tile_class);
            if (safetySpecClass.length() == 0) {
                safetySpecClass = null;
            }
        } catch (Resources.NotFoundException | NullPointerException e) {
            safetySpecClass = null;
        }
        mSafetySpec = safetySpecClass != null ? CustomTile.toSpec(new ComponentName(mContext
                .getPackageManager().getPermissionControllerPackageName(), safetySpecClass)) : null;
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
        mManagedProfileController.addCallback(mProfileCallback);

        mNightDisplayListener = mNightDisplayListenerBuilder
                .setUser(mCurrentUser.getIdentifier())
                .build();
        if (!mAutoTracker.isAdded(NIGHT)
                && ColorDisplayManager.isNightDisplayAvailable(mContext)) {
            mNightDisplayListener.setCallback(mNightDisplayCallback);
        }
        if (!mAutoTracker.isAdded(CAST)) {
            mCastController.addCallback(mCastCallback);
        }
        if (!mAutoTracker.isAdded(BRIGHTNESS) && mIsReduceBrightColorsAvailable) {
            mReduceBrightColorsController.addCallback(mReduceBrightColorsCallback);
        }
        // We always want this callback, because if the feature stops being supported,
        // we want to remove the tile from AutoAddTracker. That way it will be re-added when the
        // feature is reenabled (similar to work tile).
        mDeviceControlsController.setCallback(mDeviceControlsCallback);
        if (!mAutoTracker.isAdded(WALLET)) {
            initWalletController();
        }
        if (mSafetySpec != null) {
            if (!mAutoTracker.isAdded(mSafetySpec)) {
                initSafetyTile();
            }
            mSafetyController.addCallback(mSafetyCallback);
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
        if (ColorDisplayManager.isNightDisplayAvailable(mContext)
                && mNightDisplayListener != null) {
            mNightDisplayListener.setCallback(null);
        }
        if (mIsReduceBrightColorsAvailable) {
            mReduceBrightColorsController.removeCallback(mReduceBrightColorsCallback);
        }
        mCastController.removeCallback(mCastCallback);
        mDeviceControlsController.removeCallback();
        if (mSafetySpec != null) {
            mSafetyController.removeCallback(mSafetyCallback);
        }
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
                AutoAddSetting s = new AutoAddSetting(
                        mSecureSettings, mHandler, setting, mCurrentUser.getIdentifier(), spec);
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

    private final ManagedProfileController.Callback mProfileCallback =
            new ManagedProfileController.Callback() {
                @Override
                public void onManagedProfileChanged() {
                    if (mManagedProfileController.hasActiveProfile()) {
                        if (mAutoTracker.isAdded(WORK)) return;
                        final int position = mAutoTracker.getRestoredTilePosition(WORK);
                        mHost.addTile(WORK, position);
                        mAutoTracker.setTileAdded(WORK);
                    } else {
                        if (!mAutoTracker.isAdded(WORK)) return;
                        mHost.removeTile(WORK);
                        mAutoTracker.setTileRemoved(WORK);
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

    private final DeviceControlsController.Callback mDeviceControlsCallback =
            new DeviceControlsController.Callback() {
        @Override
        public void onControlsUpdate(@Nullable Integer position) {
            if (mAutoTracker.isAdded(DEVICE_CONTROLS)) return;
            if (position != null && !hasTile(DEVICE_CONTROLS)) {
                mHost.addTile(DEVICE_CONTROLS, position);
                mAutoTracker.setTileAdded(DEVICE_CONTROLS);
            }
            mHandler.post(() -> mDeviceControlsController.removeCallback());
        }

        @Override
        public void removeControlsAutoTracker() {
            mAutoTracker.setTileRemoved(DEVICE_CONTROLS);
        }
    };

    private boolean hasTile(String tileSpec) {
        if (tileSpec == null) return false;
        Collection<QSTile> tiles = mHost.getTiles();
        for (QSTile tile : tiles) {
            if (tileSpec.equals(tile.getTileSpec())) {
                return true;
            }
        }
        return false;
    }

    private void initWalletController() {
        if (mAutoTracker.isAdded(WALLET)) return;
        Integer position = mWalletController.getWalletPosition();

        if (position != null) {
            mHost.addTile(WALLET, position);
            mAutoTracker.setTileAdded(WALLET);
        }
    }

    private void initSafetyTile() {
        if (mSafetySpec == null || mAutoTracker.isAdded(mSafetySpec)) {
            return;
        }
        mHost.addTile(CustomTile.getComponentFromSpec(mSafetySpec), true);
        mAutoTracker.setTileAdded(mSafetySpec);
    }

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
    final ReduceBrightColorsController.Listener mReduceBrightColorsCallback =
            new ReduceBrightColorsController.Listener() {
                @Override
                public void onActivated(boolean activated) {
                    if (activated) {
                        addReduceBrightColorsTile();
                    }
                }

                private void addReduceBrightColorsTile() {
                    if (mAutoTracker.isAdded(BRIGHTNESS)) return;
                    mHost.addTile(BRIGHTNESS);
                    mAutoTracker.setTileAdded(BRIGHTNESS);
                    mHandler.post(() -> mReduceBrightColorsController.removeCallback(this));
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
    final SafetyController.Listener mSafetyCallback = new SafetyController.Listener() {
        @Override
        public void onSafetyCenterEnableChanged(boolean isSafetyCenterEnabled) {
            if (mSafetySpec == null) {
                return;
            }

            if (isSafetyCenterEnabled && !mAutoTracker.isAdded(mSafetySpec)) {
                initSafetyTile();
            } else if (!isSafetyCenterEnabled && mAutoTracker.isAdded(mSafetySpec)) {
                mHost.removeTile(mSafetySpec);
                mAutoTracker.setTileRemoved(mSafetySpec);
            }
        }
    };

    @VisibleForTesting
    protected SettingObserver getSecureSettingForKey(String key) {
        for (SettingObserver s : mAutoAddSettingList) {
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
    private class AutoAddSetting extends SettingObserver {
        private final String mSpec;

        AutoAddSetting(
                SecureSettings secureSettings,
                Handler handler,
                String setting,
                int userId,
                String tileSpec
        ) {
            super(secureSettings, handler, setting, userId);
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
                    mHost.addTile(CustomTile.getComponentFromSpec(mSpec), /* end */ true);
                } else {
                    mHost.addTile(mSpec);
                }
                mAutoTracker.setTileAdded(mSpec);
                mHandler.post(() -> setListening(false));
            }
        }
    }
}

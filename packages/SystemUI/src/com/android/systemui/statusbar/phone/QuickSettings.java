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

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.ClipDrawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;

class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkController.NetworkSignalChangedCallback,
        BatteryController.BatteryStateChangeCallback,
        LocationController.LocationGpsStateChangeCallback {

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    private Context mContext;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private State mUserState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private RefreshCallback mWifiCallback;
    private State mWifiState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private State mRSSIState = new State();

    private QuickSettingsTileView mBluetoothTile;
    private RefreshCallback mBluetoothCallback;
    private State mBluetoothState = new State();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    public QuickSettingsModel(Context context) {
        mContext = context;
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }
    void setUserTileInfo(String name) {
        mUserState.label = name;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }
    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON,
                                enabled ? 1 : 0);

        // TODO: Update the UI to reflect system setting
        // mCheckBoxPref.setChecked(enabled);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }
    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_enabled :
                R.drawable.ic_qs_airplane_normal);
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }
    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, String description) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        // TODO: Check if wifi is enabled
        mWifiState.enabled = enabled;
        mWifiState.iconId = (enabled ?
                R.drawable.ic_qs_wifi_enabled :
                R.drawable.ic_qs_wifi_normal);
        mWifiState.label = (enabled ?
                description :
                r.getString(R.string.quick_settings_wifi_no_network));
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }
    private void setRSSIState(boolean enabled) {
        // TODO: Set RSSI enabled
        // TODO: Sets the view to be "awaiting" if not already awaiting
    }
    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(boolean enabled, String description) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        // TODO: Check if RSSI is enabled
        mRSSIState.enabled = enabled;
        mRSSIState.iconId = (enabled ?
                R.drawable.ic_qs_rssi_enabled :
                R.drawable.ic_qs_rssi_normal);
        mRSSIState.label = (enabled ?
                description :
                r.getString(R.string.quick_settings_rssi_emergency_only));
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mBluetoothState.enabled) {
                    setBluetoothState(false);
                } else {
                    setBluetoothState(true);
                }
            }
        });
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        onBluetoothStateChange(adapter.isEnabled());
    }
    private void setBluetoothState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            if (enabled) {
                adapter.enable();
            } else {
                adapter.disable();
            }
        }
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = on;
        if (on) {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_enabled;
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_normal;
        }
        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
        disableLocationTile();
    }
    private void enableLocationTile() {
        mLocationTile.setVisibility(View.VISIBLE);
    }
    private void disableLocationTile() {
        mLocationTile.setVisibility(View.GONE);
    }
    // LocationController callback
    @Override
    public void onLocationGpsStateChanged(boolean inUse, String description) {
        if (inUse) {
            mLocationState.enabled = inUse;
            mLocationState.label = description;
            mLocationCallback.refreshView(mLocationTile, mLocationState);
            enableLocationTile();
        } else {
            disableLocationTile();
        }
    }

}

/**
 *
 */
class QuickSettings {

    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private QuickSettingsContainerView mContainerView;

    private CursorLoader mUserInfoLoader;

    // The set of QuickSettingsTiles that have dynamic spans (and need to be updated on
    // configuration change)
    private final ArrayList<QuickSettingsTileView> mDynamicSpannedTiles =
            new ArrayList<QuickSettingsTileView>();

    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mContext = context;
        mModel = new QuickSettingsModel(context);
        mContainerView = container;

        setupQuickSettings();
        updateResources();
    }

    void setBar(PanelBar bar) {
        mBar = bar;
    }

    void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController) {
        networkController.addNetworkSignalChangedCallback(mModel);
        bluetoothController.addStateChangedCallback(mModel);
        batteryController.addStateChangedCallback(mModel);
        locationController.addStateChangedCallback(mModel);
    }

    private void queryForUserInformation() {
        Uri userContactUri = Uri.withAppendedPath(
            ContactsContract.Profile.CONTENT_URI,
            ContactsContract.Contacts.Data.CONTENT_DIRECTORY);

        String[] selectArgs = {
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Photo.PHOTO
        };
        String where = String.format("(%s = ? OR %s = ?) AND %s IS NULL",
            ContactsContract.Contacts.Data.MIMETYPE,
            ContactsContract.Contacts.Data.MIMETYPE,
            ContactsContract.RawContacts.ACCOUNT_TYPE);
        String[] whereArgs = {
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        };

        mUserInfoLoader = new CursorLoader(mContext, userContactUri, selectArgs, where, whereArgs,
                null);
        mUserInfoLoader.registerListener(0,
                new Loader.OnLoadCompleteListener<Cursor>() {
                    @Override
                    public void onLoadComplete(Loader<Cursor> loader,
                            Cursor cursor) {
                        if (cursor.moveToFirst()) {
                            String name = cursor.getString(0); // DISPLAY_NAME
                            mModel.setUserTileInfo(name);
                            /*
                            byte[] photoData = cursor.getBlob(0);
                            Bitmap b =
                                BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
                             */
                        }
                        mUserInfoLoader.stopLoading();
                    }
                });
        mUserInfoLoader.startLoading();
    }

    private void setupQuickSettings() {
        // Setup the tiles that we are going to be showing (including the temporary ones)
        LayoutInflater inflater = LayoutInflater.from(mContext);

        addUserTiles(mContainerView, inflater);
        addSystemTiles(mContainerView, inflater);
        addTemporaryTiles(mContainerView, inflater);

        queryForUserInformation();
    }

    private void addUserTiles(ViewGroup parent, LayoutInflater inflater) {
        QuickSettingsTileView userTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        userTile.setContent(R.layout.quick_settings_tile_user, inflater);
        mModel.addUserTile(userTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.user_textview);
                tv.setText(state.label);
            }
        });
        parent.addView(userTile);
        mDynamicSpannedTiles.add(userTile);

        // Time tile
        QuickSettingsTileView timeTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        timeTile.setContent(R.layout.quick_settings_tile_time, inflater);
        parent.addView(timeTile);
        mDynamicSpannedTiles.add(timeTile);

        // Settings tile
        QuickSettingsTileView settingsTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        settingsTile.setContent(R.layout.quick_settings_tile_settings, inflater);
        settingsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        parent.addView(settingsTile);
        mDynamicSpannedTiles.add(settingsTile);
    }

    private void addSystemTiles(ViewGroup parent, LayoutInflater inflater) {
        // Wi-fi
        QuickSettingsTileView wifiTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        wifiTile.setContent(R.layout.quick_settings_tile_wifi, inflater);
        wifiTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        mModel.addWifiTile(wifiTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.wifi_textview);
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
                tv.setText(state.label);
            }
        });
        parent.addView(wifiTile);

        // RSSI
        QuickSettingsTileView rssiTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        rssiTile.setContent(R.layout.quick_settings_tile_rssi, inflater);
        rssiTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        mModel.addRSSITile(rssiTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.rssi_textview);
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
                tv.setText(state.label);
            }
        });
        parent.addView(rssiTile);

        // Battery
        QuickSettingsTileView batteryTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        batteryTile.setContent(R.layout.quick_settings_tile_battery, inflater);
        batteryTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        mModel.addBatteryTile(batteryTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                QuickSettingsModel.BatteryState batteryState =
                        (QuickSettingsModel.BatteryState) state;
                TextView tv = (TextView) view.findViewById(R.id.battery_textview);
                ClipDrawable drawable = (ClipDrawable) tv.getCompoundDrawables()[1];
                drawable.setLevel((int) (10000 * (batteryState.batteryLevel / 100.0f)));
                // TODO: use format string
                tv.setText(batteryState.batteryLevel + "%");
            }
        });
        parent.addView(batteryTile);

        // Airplane Mode
        QuickSettingsTileView airplaneTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        airplaneTile.setContent(R.layout.quick_settings_tile_airplane, inflater);
        mModel.addAirplaneModeTile(airplaneTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.airplane_mode_textview);
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
            }
        });
        parent.addView(airplaneTile);

        // Bluetooth
        QuickSettingsTileView bluetoothTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        bluetoothTile.setContent(R.layout.quick_settings_tile_bluetooth, inflater);
        mModel.addBluetoothTile(bluetoothTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.bluetooth_textview);
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
            }
        });
        parent.addView(bluetoothTile);

        // Brightness
        QuickSettingsTileView brightnessTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        brightnessTile.setContent(R.layout.quick_settings_tile_brightness, inflater);
        brightnessTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        parent.addView(brightnessTile);
    }

    private void addTemporaryTiles(final ViewGroup parent, final LayoutInflater inflater) {
        // Location
        QuickSettingsTileView locationTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        locationTile.setContent(R.layout.quick_settings_tile_location, inflater);
        locationTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent =
                        new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                mBar.collapseAllPanels(true);
            }
        });
        mModel.addLocationTile(locationTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.location_textview);
                tv.setText(state.label);
            }
        });
        parent.addView(locationTile);

        /*
        QuickSettingsTileView mediaTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        mediaTile.setContent(R.layout.quick_settings_tile_media, inflater);
        parent.addView(mediaTile);
        QuickSettingsTileView imeTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        imeTile.setContent(R.layout.quick_settings_tile_ime, inflater);
        imeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parent.removeViewAt(0);
            }
        });
        parent.addView(imeTile);
        */
    }

    void updateResources() {
        Resources r = mContext.getResources();

        // Update the User, Time, and Settings tiles spans, and reset everything else
        int span = r.getInteger(R.integer.quick_settings_user_time_settings_tile_span);
        for (QuickSettingsTileView v : mDynamicSpannedTiles) {
            v.setColumnSpan(span);
        }
        mContainerView.requestLayout();
    }
}
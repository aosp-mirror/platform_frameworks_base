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

import com.android.internal.view.RotationPolicy;
import com.android.systemui.R;

import com.android.systemui.statusbar.phone.QuickSettingsModel.BluetoothState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.RSSIState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.phone.QuickSettingsModel.UserState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.WifiState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplayStatus;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;


/**
 *
 */
class QuickSettings {
    static final boolean DEBUG_GONE_TILES = false;
    private static final String TAG = "QuickSettings";
    public static final boolean SHOW_IME_TILE = false;

    public static final boolean LONG_PRESS_TOGGLES = true;

    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private ViewGroup mContainerView;

    private DisplayManager mDisplayManager;
    private WifiDisplayStatus mWifiDisplayStatus;
    private PhoneStatusBar mStatusBarService;
    private BluetoothState mBluetoothState;
    private BluetoothAdapter mBluetoothAdapter;
    private WifiManager mWifiManager;

    private BluetoothController mBluetoothController;

    private AsyncTask<Void, Void, Pair<String, Drawable>> mUserInfoTask;

    private LevelListDrawable mBatteryLevels;
    private LevelListDrawable mChargingBatteryLevels;

    boolean mTilesSetUp = false;
    boolean mUseDefaultAvatar = false;

    private Handler mHandler;

    // The set of QuickSettingsTiles that have dynamic spans (and need to be updated on
    // configuration change)
    private final ArrayList<QuickSettingsTileView> mDynamicSpannedTiles =
            new ArrayList<QuickSettingsTileView>();

    private final RotationPolicy.RotationPolicyListener mRotationPolicyListener =
            new RotationPolicy.RotationPolicyListener() {
        @Override
        public void onChange() {
            mModel.onRotationLockChanged();
        }
    };

    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mContext = context;
        mContainerView = container;
        mModel = new QuickSettingsModel(context);
        mWifiDisplayStatus = new WifiDisplayStatus();
        mBluetoothState = new QuickSettingsModel.BluetoothState();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        mHandler = new Handler();

        Resources r = mContext.getResources();
        mBatteryLevels = (LevelListDrawable) r.getDrawable(R.drawable.qs_sys_battery);
        mChargingBatteryLevels =
                (LevelListDrawable) r.getDrawable(R.drawable.qs_sys_battery_charging);

        IntentFilter filter = new IntentFilter();
        filter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                null, null);
    }

    void setBar(PanelBar bar) {
        mBar = bar;
    }

    public void setService(PhoneStatusBar phoneStatusBar) {
        mStatusBarService = phoneStatusBar;
    }

    public PhoneStatusBar getService() {
        return mStatusBarService;
    }

    public void setImeWindowStatus(boolean visible) {
        mModel.onImeWindowStatusChanged(visible);
    }

    void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController) {
        mBluetoothController = bluetoothController;

        setupQuickSettings();
        updateWifiDisplayStatus();
        updateResources();

        networkController.addNetworkSignalChangedCallback(mModel);
        bluetoothController.addStateChangedCallback(mModel);
        batteryController.addStateChangedCallback(mModel);
        locationController.addStateChangedCallback(mModel);
        RotationPolicy.registerRotationPolicyListener(mContext, mRotationPolicyListener,
                UserHandle.USER_ALL);
    }

    private void queryForUserInformation() {
        Context currentUserContext = null;
        UserInfo userInfo = null;
        try {
            userInfo = ActivityManagerNative.getDefault().getCurrentUser();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userInfo.id));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get user info", e);
        }
        final int userId = userInfo.id;
        final String userName = userInfo.name;

        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                final UserManager um = UserManager.get(mContext);

                // Fall back to the UserManager nickname if we can't read the name from the local
                // profile below.
                String name = userName;
                Drawable avatar = null;
                Bitmap rawAvatar = um.getUserIcon(userId);
                if (rawAvatar != null) {
                    avatar = new BitmapDrawable(mContext.getResources(), rawAvatar);
                } else {
                    avatar = mContext.getResources().getDrawable(R.drawable.ic_qs_default_user);
                    mUseDefaultAvatar = true;
                }

                // If it's a single-user device, get the profile name, since the nickname is not
                // usually valid
                if (um.getUsers().size() <= 1) {
                    // Try and read the display name from the local profile
                    final Cursor cursor = context.getContentResolver().query(
                            Profile.CONTENT_URI, new String[] {Phone._ID, Phone.DISPLAY_NAME},
                            null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(Phone.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                mModel.setUserTileInfo(result.first, result.second);
                mUserInfoTask = null;
            }
        };
        mUserInfoTask.execute();
    }

    private void setupQuickSettings() {
        // Setup the tiles that we are going to be showing (including the temporary ones)
        LayoutInflater inflater = LayoutInflater.from(mContext);

        addUserTiles(mContainerView, inflater);
        addSystemTiles(mContainerView, inflater);
        addTemporaryTiles(mContainerView, inflater);

        queryForUserInformation();
        mTilesSetUp = true;
    }

    private void startSettingsActivity(String action) {
        Intent intent = new Intent(action);
        startSettingsActivity(intent);
    }

    private void startSettingsActivity(Intent intent) {
        startSettingsActivity(intent, true);
    }

    private void collapsePanels() {
        getService().animateCollapsePanels();
    }

    private void startSettingsActivity(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !getService().isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        collapsePanels();
    }

    private void addUserTiles(ViewGroup parent, LayoutInflater inflater) {
        QuickSettingsTileView userTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        userTile.setContent(R.layout.quick_settings_tile_user, inflater);
        userTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                final UserManager um = UserManager.get(mContext);
                if (um.getUsers(true).size() > 1) {
                    try {
                        WindowManagerGlobal.getWindowManagerService().lockNow(null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Couldn't show user switcher", e);
                    }
                } else {
                    Intent intent = ContactsContract.QuickContact.composeQuickContactsIntent(
                            mContext, v, ContactsContract.Profile.CONTENT_URI,
                            ContactsContract.QuickContact.MODE_LARGE, null);
                    mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
                }
            }
        });
        mModel.addUserTile(userTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                UserState us = (UserState) state;
                ImageView iv = (ImageView) view.findViewById(R.id.user_imageview);
                TextView tv = (TextView) view.findViewById(R.id.user_textview);
                tv.setText(state.label);
                iv.setImageDrawable(us.avatar);
                view.setContentDescription(mContext.getString(
                        R.string.accessibility_quick_settings_user, state.label));
            }
        });
        parent.addView(userTile);
        mDynamicSpannedTiles.add(userTile);

        // Brightness
        final QuickSettingsBasicTile brightnessTile
                = new QuickSettingsBasicTile(mContext);
        brightnessTile.setImageResource(R.drawable.ic_qs_brightness_auto_off);
        brightnessTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                showBrightnessDialog();
            }
        });
        mModel.addBrightnessTile(brightnessTile,
                new QuickSettingsModel.BasicRefreshCallback(brightnessTile));
        parent.addView(brightnessTile);
        mDynamicSpannedTiles.add(brightnessTile);

        // Time tile
        /*
        QuickSettingsTileView timeTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        timeTile.setContent(R.layout.quick_settings_tile_time, inflater);
        timeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Quick. Clock. Quick. Clock. Quick. Clock.
                startSettingsActivity(Intent.ACTION_QUICK_CLOCK);
            }
        });
        mModel.addTimeTile(timeTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State alarmState) {}
        });
        parent.addView(timeTile);
        mDynamicSpannedTiles.add(timeTile);
        */

        // Settings tile
        final QuickSettingsBasicTile settingsTile = new QuickSettingsBasicTile(mContext);
        settingsTile.setImageResource(R.drawable.ic_qs_settings);
        settingsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_SETTINGS);
            }
        });
        mModel.addSettingsTile(settingsTile,
                new QuickSettingsModel.BasicRefreshCallback(settingsTile));
        parent.addView(settingsTile);
        mDynamicSpannedTiles.add(settingsTile);
    }

    private void addSystemTiles(ViewGroup parent, LayoutInflater inflater) {
        // Wi-fi
        final QuickSettingsBasicTile wifiTile
                = new QuickSettingsBasicTile(mContext);
        wifiTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_WIFI_SETTINGS);
            }
        });
        if (LONG_PRESS_TOGGLES) {
            wifiTile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    final boolean enable =
                            (mWifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED);
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... args) {
                            // Disable tethering if enabling Wifi
                            final int wifiApState = mWifiManager.getWifiApState();
                            if (enable && ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                                           (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                                mWifiManager.setWifiApEnabled(null, false);
                            }

                            mWifiManager.setWifiEnabled(enable);
                            return null;
                        }
                    }.execute();
                    wifiTile.setPressed(false);
                    return true;
                }} );
        }
        mModel.addWifiTile(wifiTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State state) {
                WifiState wifiState = (WifiState) state;
                wifiTile.setImageResource(wifiState.iconId);
                wifiTile.setText(wifiState.label);
                wifiTile.setContentDescription(mContext.getString(
                        R.string.accessibility_quick_settings_wifi,
                        wifiState.signalContentDescription,
                        (wifiState.connected) ? wifiState.label : ""));
            }
        });
        parent.addView(wifiTile);

        if (mModel.deviceHasMobileData()) {
            // RSSI
            QuickSettingsTileView rssiTile = (QuickSettingsTileView)
                    inflater.inflate(R.layout.quick_settings_tile, parent, false);
            rssiTile.setContent(R.layout.quick_settings_tile_rssi, inflater);
            rssiTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings$DataUsageSummaryActivity"));
                    startSettingsActivity(intent);
                }
            });
            mModel.addRSSITile(rssiTile, new QuickSettingsModel.RefreshCallback() {
                @Override
                public void refreshView(QuickSettingsTileView view, State state) {
                    RSSIState rssiState = (RSSIState) state;
                    ImageView iv = (ImageView) view.findViewById(R.id.rssi_image);
                    ImageView iov = (ImageView) view.findViewById(R.id.rssi_overlay_image);
                    TextView tv = (TextView) view.findViewById(R.id.rssi_textview);
                    // Force refresh
                    iv.setImageDrawable(null);
                    iv.setImageResource(rssiState.signalIconId);

                    if (rssiState.dataTypeIconId > 0) {
                        iov.setImageResource(rssiState.dataTypeIconId);
                    } else {
                        iov.setImageDrawable(null);
                    }
                    tv.setText(state.label);
                    view.setContentDescription(mContext.getResources().getString(
                            R.string.accessibility_quick_settings_mobile,
                            rssiState.signalContentDescription, rssiState.dataContentDescription,
                            state.label));
                }
            });
            parent.addView(rssiTile);
        }

        // Rotation Lock
        if (mContext.getResources().getBoolean(R.bool.quick_settings_show_rotation_lock)
                || DEBUG_GONE_TILES) {
            final QuickSettingsBasicTile rotationLockTile
                    = new QuickSettingsBasicTile(mContext);
            rotationLockTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean locked = RotationPolicy.isRotationLocked(mContext);
                    RotationPolicy.setRotationLock(mContext, !locked);
                }
            });
            mModel.addRotationLockTile(rotationLockTile,
                    new QuickSettingsModel.BasicRefreshCallback(rotationLockTile));
            parent.addView(rotationLockTile);
        }

        // Battery
        final QuickSettingsBasicTile batteryTile = new QuickSettingsBasicTile(mContext);
        batteryTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
            }
        });
        mModel.addBatteryTile(batteryTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State state) {
                QuickSettingsModel.BatteryState batteryState =
                        (QuickSettingsModel.BatteryState) state;
                Drawable d = batteryState.pluggedIn
                        ? mChargingBatteryLevels
                        : mBatteryLevels;
                String t;
                if (batteryState.batteryLevel == 100) {
                    t = mContext.getString(R.string.quick_settings_battery_charged_label);
                } else {
                    t = batteryState.pluggedIn
                        ? mContext.getString(R.string.quick_settings_battery_charging_label,
                                batteryState.batteryLevel)
                        : mContext.getString(R.string.status_bar_settings_battery_meter_format,
                                batteryState.batteryLevel);
                }
                batteryTile.setImageDrawable(d);
                batteryTile.getImageView().setImageLevel(batteryState.batteryLevel);
                batteryTile.setText(t);
                batteryTile.setContentDescription(
                        mContext.getString(R.string.accessibility_quick_settings_battery, t));
            }
        });
        parent.addView(batteryTile);

        // Airplane Mode
        final QuickSettingsBasicTile airplaneTile
                = new QuickSettingsBasicTile(mContext);
        mModel.addAirplaneModeTile(airplaneTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State state) {
                airplaneTile.setImageResource(state.iconId);

                String airplaneState = mContext.getString(
                        (state.enabled) ? R.string.accessibility_desc_on
                                : R.string.accessibility_desc_off);
                airplaneTile.setContentDescription(
                        mContext.getString(R.string.accessibility_quick_settings_airplane, airplaneState));
                airplaneTile.setText(state.label);
            }
        });
        parent.addView(airplaneTile);

        // Bluetooth
        if (mModel.deviceSupportsBluetooth()
                || DEBUG_GONE_TILES) {
            final QuickSettingsBasicTile bluetoothTile
                    = new QuickSettingsBasicTile(mContext);
            bluetoothTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSettingsActivity(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                }
            });
            if (LONG_PRESS_TOGGLES) {
                bluetoothTile.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (mBluetoothAdapter.isEnabled()) {
                            mBluetoothAdapter.disable();
                        } else {
                            mBluetoothAdapter.enable();
                        }
                        bluetoothTile.setPressed(false);
                        return true;
                    }});
            }
            mModel.addBluetoothTile(bluetoothTile, new QuickSettingsModel.RefreshCallback() {
                @Override
                public void refreshView(QuickSettingsTileView unused, State state) {
                    BluetoothState bluetoothState = (BluetoothState) state;
                    bluetoothTile.setImageResource(state.iconId);

                    /*
                    Resources r = mContext.getResources();
                    //TODO: Show connected bluetooth device label
                    Set<BluetoothDevice> btDevices =
                            mBluetoothController.getBondedBluetoothDevices();
                    if (btDevices.size() == 1) {
                        // Show the name of the bluetooth device you are connected to
                        label = btDevices.iterator().next().getName();
                    } else if (btDevices.size() > 1) {
                        // Show a generic label about the number of bluetooth devices
                        label = r.getString(R.string.quick_settings_bluetooth_multiple_devices_label,
                                btDevices.size());
                    }
                    */
                    bluetoothTile.setContentDescription(mContext.getString(
                            R.string.accessibility_quick_settings_bluetooth,
                            bluetoothState.stateContentDescription));
                    bluetoothTile.setText(state.label);
                }
            });
            parent.addView(bluetoothTile);
        }

    }

    private void addTemporaryTiles(final ViewGroup parent, final LayoutInflater inflater) {
        // Alarm tile
        final QuickSettingsBasicTile alarmTile
                = new QuickSettingsBasicTile(mContext);
        alarmTile.setImageResource(R.drawable.ic_qs_alarm_on);
        alarmTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: Jump into the alarm application
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.deskclock",
                        "com.android.deskclock.AlarmClock"));
                startSettingsActivity(intent);
            }
        });
        mModel.addAlarmTile(alarmTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State alarmState) {
                alarmTile.setText(alarmState.label);
                alarmTile.setVisibility(alarmState.enabled ? View.VISIBLE : View.GONE);
                alarmTile.setContentDescription(mContext.getString(
                        R.string.accessibility_quick_settings_alarm, alarmState.label));
            }
        });
        parent.addView(alarmTile);

        // Location
        final QuickSettingsBasicTile locationTile
                = new QuickSettingsBasicTile(mContext);
        locationTile.setImageResource(R.drawable.ic_qs_location);
        locationTile.setTextResource(R.string.quick_settings_location_label);
        locationTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            }
        });
        mModel.addLocationTile(locationTile,
                new QuickSettingsModel.BasicRefreshCallback(locationTile)
                        .setShowWhenEnabled(true));
        parent.addView(locationTile);

        // Wifi Display
        QuickSettingsBasicTile wifiDisplayTile
                = new QuickSettingsBasicTile(mContext);
        wifiDisplayTile.setImageResource(R.drawable.ic_qs_remote_display);
        wifiDisplayTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_WIFI_DISPLAY_SETTINGS);
            }
        });
        mModel.addWifiDisplayTile(wifiDisplayTile,
                new QuickSettingsModel.BasicRefreshCallback(wifiDisplayTile)
                        .setShowWhenEnabled(true));
        parent.addView(wifiDisplayTile);

        if (SHOW_IME_TILE || DEBUG_GONE_TILES) {
            // IME
            final QuickSettingsBasicTile imeTile
                    = new QuickSettingsBasicTile(mContext);
            imeTile.setImageResource(R.drawable.ic_qs_ime);
            imeTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        collapsePanels();
                        Intent intent = new Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER);
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                        pendingIntent.send();
                    } catch (Exception e) {}
                }
            });
            mModel.addImeTile(imeTile,
                    new QuickSettingsModel.BasicRefreshCallback(imeTile)
                            .setShowWhenEnabled(true));
            parent.addView(imeTile);
        }

        // Bug reports
        final QuickSettingsBasicTile bugreportTile
                = new QuickSettingsBasicTile(mContext);
        bugreportTile.setImageResource(com.android.internal.R.drawable.stat_sys_adb);
        bugreportTile.setTextResource(com.android.internal.R.string.bugreport_title);
        bugreportTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                showBugreportDialog();
            }
        });
        mModel.addBugreportTile(bugreportTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(bugreportTile);
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

        // Update the model
        mModel.updateResources();

        // Update the User, Time, and Settings tiles spans, and reset everything else
        int span = r.getInteger(R.integer.quick_settings_user_time_settings_tile_span);
        for (QuickSettingsTileView v : mDynamicSpannedTiles) {
            v.setColumnSpan(span);
        }
        ((QuickSettingsContainerView)mContainerView).updateResources();
        mContainerView.requestLayout();
    }


    private void showBrightnessDialog() {
        Intent intent = new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        mContext.sendBroadcast(intent);
    }

    private void showBugreportDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setPositiveButton(com.android.internal.R.string.report, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Add a little delay before executing, to give the
                    // dialog a chance to go away before it takes a
                    // screenshot.
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ActivityManagerNative.getDefault()
                                        .requestBugReport();
                            } catch (RemoteException e) {
                            }
                        }
                    }, 500);
                }
            }
        });
        builder.setMessage(com.android.internal.R.string.bugreport_message);
        builder.setTitle(com.android.internal.R.string.bugreport_title);
        builder.setCancelable(true);
        final Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
        } catch (RemoteException e) {
        }
        dialog.show();
    }

    private void updateWifiDisplayStatus() {
        mWifiDisplayStatus = mDisplayManager.getWifiDisplayStatus();
        applyWifiDisplayStatus();
    }

    private void applyWifiDisplayStatus() {
        mModel.onWifiDisplayStateChanged(mWifiDisplayStatus);
    }

    private void applyBluetoothStatus() {
        mModel.onBluetoothStateChange(mBluetoothState);
    }

    void reloadUserInfo() {
        if (mUserInfoTask != null) {
            mUserInfoTask.cancel(false);
            mUserInfoTask = null;
        }
        if (mTilesSetUp) {
            queryForUserInformation();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED.equals(action)) {
                WifiDisplayStatus status = (WifiDisplayStatus)intent.getParcelableExtra(
                        DisplayManager.EXTRA_WIFI_DISPLAY_STATUS);
                mWifiDisplayStatus = status;
                applyWifiDisplayStatus();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                mBluetoothState.enabled = (state == BluetoothAdapter.STATE_ON);
                applyBluetoothStatus();
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int status = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mBluetoothState.connected = (status == BluetoothAdapter.STATE_CONNECTED);
                applyBluetoothStatus();
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                reloadUserInfo();
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                if (mUseDefaultAvatar) {
                    queryForUserInformation();
                }
            }
        }
    };

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
                    Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                try {
                    final int currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
                    final int changedUser =
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                    if (changedUser == currentUser) {
                        reloadUserInfo();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't get current user id for profile change", e);
                }
            }

        }
    };
}

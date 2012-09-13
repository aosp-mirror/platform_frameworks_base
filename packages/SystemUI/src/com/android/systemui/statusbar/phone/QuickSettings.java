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

import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.ClipDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BrightnessController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.ToggleSlider;

import java.util.ArrayList;
import java.util.Comparator;


/**
 *
 */
class QuickSettings {

    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private QuickSettingsContainerView mContainerView;

    private DisplayManager mDisplayManager;
    private WifiDisplayStatus mWifiDisplayStatus;
    private WifiDisplayListAdapter mWifiDisplayListAdapter;
    
    private BrightnessController mBrightnessController;
    private Dialog mBrightnessDialog;

    private CursorLoader mUserInfoLoader;

    // The set of QuickSettingsTiles that have dynamic spans (and need to be updated on
    // configuration change)
    private final ArrayList<QuickSettingsTileView> mDynamicSpannedTiles =
            new ArrayList<QuickSettingsTileView>();

    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mContext = context;
        mContainerView = container;
        mModel = new QuickSettingsModel(context);
        mWifiDisplayStatus = new WifiDisplayStatus();
        mWifiDisplayListAdapter = new WifiDisplayListAdapter(context);

        IntentFilter filter = new IntentFilter();
        filter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        setupQuickSettings();
        updateWifiDisplayStatus();
        updateResources();
    }

    void setBar(PanelBar bar) {
        mBar = bar;
    }

    public void setImeWindowStatus(boolean visible) {
        mModel.onImeWindowStatusChanged(visible);
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
                        if (cursor != null && cursor.moveToFirst()) {
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

    private void startSettingsActivity(String action) {
        Intent intent = new Intent(action);
        startSettingsActivity(intent);
    }
    private void startSettingsActivity(Intent intent) {
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mBar.collapseAllPanels(true);
        mContext.startActivity(intent);
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
        timeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: Jump into the alarm application
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.google.android.deskclock",
                        "com.android.deskclock.AlarmClock"));
                startSettingsActivity(intent);
            }
        });
        mModel.addTimeTile(timeTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State alarmState) {
                TextView tv = (TextView) view.findViewById(R.id.alarm_textview);
                tv.setText(alarmState.label);
                tv.setVisibility(alarmState.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(timeTile);
        mDynamicSpannedTiles.add(timeTile);

        // Settings tile
        QuickSettingsTileView settingsTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        settingsTile.setContent(R.layout.quick_settings_tile_settings, inflater);
        settingsTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(android.provider.Settings.ACTION_SETTINGS);
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
                startSettingsActivity(android.provider.Settings.ACTION_WIFI_SETTINGS);
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

        if (mModel.deviceSupportsTelephony()) {
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
                    TextView tv = (TextView) view.findViewById(R.id.rssi_textview);
                    tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
                    tv.setText(state.label);
                }
            });
            parent.addView(rssiTile);
        }

        // Battery
        QuickSettingsTileView batteryTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        batteryTile.setContent(R.layout.quick_settings_tile_battery, inflater);
        batteryTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
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
        if (mModel.deviceSupportsBluetooth()) {
            QuickSettingsTileView bluetoothTile = (QuickSettingsTileView)
                    inflater.inflate(R.layout.quick_settings_tile, parent, false);
            bluetoothTile.setContent(R.layout.quick_settings_tile_bluetooth, inflater);
            bluetoothTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSettingsActivity(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                }
            });
            mModel.addBluetoothTile(bluetoothTile, new QuickSettingsModel.RefreshCallback() {
                @Override
                public void refreshView(QuickSettingsTileView view, State state) {
                    TextView tv = (TextView) view.findViewById(R.id.bluetooth_textview);
                    tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, state.iconId, 0, 0);
                }
            });
            parent.addView(bluetoothTile);
        }

        // Brightness
        QuickSettingsTileView brightnessTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        brightnessTile.setContent(R.layout.quick_settings_tile_brightness, inflater);
        brightnessTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // startSettingsActivity(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                mBar.collapseAllPanels(true);
                showBrightnessDialog();
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
                startSettingsActivity(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            }
        });
        mModel.addLocationTile(locationTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.location_textview);
                tv.setText(state.label);
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(locationTile);

        // Wifi Display
        QuickSettingsTileView wifiDisplayTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        wifiDisplayTile.setContent(R.layout.quick_settings_tile_wifi_display, inflater);
        wifiDisplayTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mBar.collapseAllPanels(true);
                showWifiDisplayDialog();
            }
        });
        mModel.addWifiDisplayTile(wifiDisplayTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.wifi_display_textview);
                tv.setText(state.label);
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(wifiDisplayTile);

        // IME
        QuickSettingsTileView imeTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        imeTile.setContent(R.layout.quick_settings_tile_ime, inflater);
        imeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    mBar.collapseAllPanels(true);
                    Intent intent = new Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER);
                    PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                    pendingIntent.send();
                } catch (Exception e) {}
            }
        });
        mModel.addImeTile(imeTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                TextView tv = (TextView) view.findViewById(R.id.ime_textview);
                if (state.label != null) {
                    tv.setText(state.label);
                }
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(imeTile);

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
    
    private void showBrightnessDialog() {
        if (mBrightnessDialog == null) {
            mBrightnessDialog = new Dialog(mContext);
            mBrightnessDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            mBrightnessDialog.setContentView(R.layout.quick_settings_brightness_dialog);
            mBrightnessDialog.setCanceledOnTouchOutside(true);
        
            mBrightnessController = new BrightnessController(mContext,
                    (ToggleSlider) mBrightnessDialog.findViewById(R.id.brightness_slider));
            mBrightnessDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    mBrightnessController = null;
                }
            });
            
            mBrightnessDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mBrightnessDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        if (!mBrightnessDialog.isShowing()) {
            mBrightnessDialog.show();
        }
    }

    // Wifi Display
    private void showWifiDisplayDialog() {
        mDisplayManager.scanWifiDisplays();
        updateWifiDisplayStatus();

        Dialog dialog = new Dialog(mContext);
        dialog.setContentView(R.layout.wifi_display_dialog);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setTitle(R.string.wifi_display_dialog_title);

        Button scanButton = (Button)dialog.findViewById(R.id.scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDisplayManager.scanWifiDisplays();
            }
        });

        Button disconnectButton = (Button)dialog.findViewById(R.id.disconnect);
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDisplayManager.disconnectWifiDisplay();
            }
        });

        ListView list = (ListView)dialog.findViewById(R.id.list);
        list.setAdapter(mWifiDisplayListAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                WifiDisplay display = mWifiDisplayListAdapter.getItem(position);
                mDisplayManager.connectWifiDisplay(display.getDeviceAddress());
            }
        });

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void updateWifiDisplayStatus() {
        applyWifiDisplayStatus(mDisplayManager.getWifiDisplayStatus());
    }

    private void applyWifiDisplayStatus(WifiDisplayStatus status) {
        mWifiDisplayStatus = status;

        mWifiDisplayListAdapter.clear();
        mWifiDisplayListAdapter.addAll(status.getKnownDisplays());
        if (status.getActiveDisplay() != null
                && !contains(status.getKnownDisplays(), status.getActiveDisplay())) {
            mWifiDisplayListAdapter.add(status.getActiveDisplay());
        }
        mWifiDisplayListAdapter.sort(mWifiDisplayComparator);

        mModel.onWifiDisplayStateChanged(status);
    }

    private static boolean contains(WifiDisplay[] displays, WifiDisplay display) {
        for (WifiDisplay d : displays) {
            if (d.equals(display)) {
                return true;
            }
        }
        return false;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED)) {
                WifiDisplayStatus status = (WifiDisplayStatus)intent.getParcelableExtra(
                        DisplayManager.EXTRA_WIFI_DISPLAY_STATUS);
                applyWifiDisplayStatus(status);
            }
        }
    };

    private final class WifiDisplayListAdapter extends ArrayAdapter<WifiDisplay> {
        private final LayoutInflater mInflater;

        public WifiDisplayListAdapter(Context context) {
            super(context, android.R.layout.simple_list_item_2);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            WifiDisplay item = getItem(position);
            View view = convertView;
            if (view == null) {
                view = mInflater.inflate(android.R.layout.simple_list_item_2,
                        parent, false);
            }
            TextView headline = (TextView) view.findViewById(android.R.id.text1);
            TextView subText = (TextView) view.findViewById(android.R.id.text2);
            headline.setText(item.getDeviceName());

            int state = WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED;
            if (item.equals(mWifiDisplayStatus.getActiveDisplay())) {
                state = mWifiDisplayStatus.getActiveDisplayState();
            }
            switch (state) {
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTING:
                    subText.setText(R.string.wifi_display_state_connecting);
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTED:
                    subText.setText(R.string.wifi_display_state_connected);
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED:
                default:
                    subText.setText(R.string.wifi_display_state_available);
                    break;
            }
            return view;
        }
    }

    private final Comparator<WifiDisplay> mWifiDisplayComparator = new Comparator<WifiDisplay>() {
        @Override
        public int compare(WifiDisplay lhs, WifiDisplay rhs) {
            int c = lhs.getDeviceName().compareToIgnoreCase(rhs.getDeviceName());
            if (c == 0) {
                c = lhs.getDeviceAddress().compareToIgnoreCase(rhs.getDeviceAddress());
            }
            return c;
        }
    };
}
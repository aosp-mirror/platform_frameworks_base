/*
 * Copyright (C) 2015-2016 DirtyUnicorns
 *
 * Author: Randall Rushing aka Bigrushdog <randall.rushing@gmail.com>
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

package com.android.systemui.statusbar.policy;

import java.io.File;
import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.utils.du.*;
import com.android.internal.utils.du.DUPackageMonitor.PackageState;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.BaseStatusBarHeader;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;

public class MinitBatteryController implements
        DUPackageMonitor.PackageChangedListener,
        MinitBattery.OnMinitBatteryErrorListener {
    private static final String TAG = MinitBatteryController.class
            .getSimpleName();

    private static final String RESOURCE_APP = "com.three.minit.batteryresources";
    private static final String SETTINGS_PAID = "com.three.minit.minitbatterysettings";
    private static final String SETTINGS_FREE = "com.three.minit.minitbatterysettings.free";

    private Context mContext;
    private ContentResolver mResolver;
    private ArrayList<MinitBattery> mMinitList = new ArrayList<MinitBattery>(2);
    private MinitState mState = MinitState.DISABLED;
    private MinitSettings mSettings;
    private int mLevel = 0;
    private int mStatus = 0;
    private ResourceManager mRM;
    private boolean mHasSettings;

    /**
     * State of 3Minit battery mod
     *
     * DISABLED: No settings or resource apps are installed, initial boot configuration
     * ENABLED: Device has at least the a settings app or resource app installed
     * SETUP: Resources have been confirmed, feature functional
     * ERROR: An exception was thown, feature disabled until next reboot
     */
    enum MinitState {
        DISABLED, ENABLED, SETUP, ERROR
    }

    class ResourceManager {
        private Context mResourceContext;
        private Resources mRes;
        private boolean mResExists = false;

        public ResourceManager(Context context) {
            try {
                mResourceContext = context.createPackageContext(RESOURCE_APP,
                        Context.CONTEXT_IGNORE_SECURITY);
                mRes = mResourceContext.getResources();
                mResExists = true;
            } catch (PackageManager.NameNotFoundException e) {
            }
        }

        @SuppressWarnings("deprecation")
        public Drawable getDrawable(String name) {
            return mRes.getDrawable(getResourceId(name, "drawable"));
        }

        public int getResourceId(String name, String type) {

            return mRes.getIdentifier(name, type,
                    mResourceContext.getPackageName());
        }

        public boolean resourcesExists() {
            return mResExists;
        }
    }

    private DUSystemReceiver mReceiver = new DUSystemReceiver() {
        @Override
        protected boolean onExemptBroadcast(Context context, String packageName) {
            return TextUtils.equals(packageName, SETTINGS_FREE)
                    || TextUtils.equals(packageName, SETTINGS_PAID);
        }

        @Override
        protected void onSecureReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    "com.three.minit.BATTERY_TYPE_CHANGED")) {
                if (mState != MinitState.SETUP && !mRM.resourcesExists()) {
                    // validate possible state in which we don't have resource
                    // app and we didn't detect icons at initialization
                    // Perhaps user just downloaded settings app but hasn't
                    // downloaded any icons yet
                    updateSettings();
                    if (mSettings.mWorkingType != 0) {
                        mState = mSettings.hasIconsInStorage() ? MinitState.SETUP
                                : MinitState.ENABLED;
                    }
                }
                updateSettings();
            } else if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                mLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                mStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                updateBattery(mStatus, mLevel);
            }
            if (mState == MinitState.SETUP) {
                updateImage();
            }
        }
    };

    static class MinitSettings {
        static final String mBatteryIconsLocation = Environment
                .getExternalStorageDirectory().getAbsolutePath()
                + File.separator + "3MinitBatteryIcons";

        String mDownloadBatteryIconsLoaction;
        int mChargeAnim = 0;
        int mBatteryType = 8;
        int mWorkingType = 0;
        boolean mIsColorable = false;
        int mBatteryColor = Color.WHITE;
        int mBatteryMidColor = 0xFFFFC400;
        int mBatteryLowColor = 0xFFDE2904;
        int mMidLevel = 50;
        int mLowLevel = 20;
        int mTextColor = Color.WHITE;
        int mTextSize = 30;
        boolean mVisible;

        private String getSaveLocation(ContentResolver cr) {
            String t = Settings.System.getString(cr, "save_loc");
            if (t != null) {
                return t + "/3Minit Downloads/BatteryIcons/";
            } else {
                return Environment.getExternalStorageDirectory().getPath()
                        + "/3Minit Downloads/BatteryIcons/";
            }
        }

        private void updateSaveLocation(ContentResolver cr) {
            mDownloadBatteryIconsLoaction = getSaveLocation(cr);
        }

        void init(ContentResolver cr) {
            File file = new File(mBatteryIconsLocation);
            file.mkdirs();
            updateSaveLocation(cr);
        }

        boolean hasIconsInStorage() {
            // sampling for 2 sets of 2 is a fair assessment
            boolean hasIcons = hasNormalDrawable(1, 5)
                    && hasNormalDrawable(1, 80);
            if (!hasIcons) {
                // try the user folder
                hasIcons = hasNormalDrawable(2, 5) && hasNormalDrawable(2, 80);
            }
            return hasIcons;
        }

        private boolean hasNormalDrawable(int workingType, int level) {
            String path = workingType == 1 ? mDownloadBatteryIconsLoaction
                    : mBatteryIconsLocation;
            return new File(path + "stat_sys_battery_" + String.valueOf(level)
                    + ".png").exists();
        }

        void getSettings(ContentResolver cr, boolean hasResources) {
            updateSaveLocation(cr);
            mChargeAnim = Settings.System.getInt(cr, "minit_anim_type", 0);
            mBatteryType = Settings.System.getInt(cr, "minit_battery_type", 8);
            mWorkingType = Settings.System.getInt(cr, "minit_working_type", 0);

            // user selected offline icons without the resources pack
            // safeguard system by overriding value and
            // validate icons in storage and reset setup flag if needed
            if (mWorkingType == 0 && !hasResources) {
                // we must already be set up for this to work
                mWorkingType = 1;
            }

            mIsColorable = Settings.System.getInt(cr, "minit_colorable", 0) == 1;
            mBatteryColor = Settings.System.getInt(cr, "minit_battery_color",
                    mBatteryColor);
            mBatteryMidColor = Settings.System.getInt(cr,
                    "minit_battery_mid_color", mBatteryMidColor);
            mBatteryLowColor = Settings.System.getInt(cr,
                    "minit_battery_low_color", mBatteryLowColor);
            mMidLevel = Settings.System
                    .getInt(cr, "minit_mid_level", mMidLevel);
            mLowLevel = Settings.System
                    .getInt(cr, "minit_low_level", mLowLevel);
            mTextSize = Settings.System.getInt(cr, "minit_battery_text_size",
                    30);
            mTextColor = Settings.System.getInt(cr, "minit_battery_text_color",
                    mTextColor);
            mVisible = Settings.System.getInt(cr, "minit_battery_visible", 1) == 1;
        }
    }

    public MinitBatteryController(Context context,
            PhoneStatusBarView statusbarView,
            KeyguardStatusBarView keyguardView) {
        mContext = context;
        mResolver = context.getContentResolver();

        // get our 3 instances from all over SystemUILand
        ViewGroup systemIconsSuperContainer = (ViewGroup) keyguardView
                .findViewById(R.id.system_icons_super_container);

        // we only need error callbacks from one instance. If one fails they all
        // fail
        MinitBattery statusbarMinit = (MinitBattery) statusbarView
                .findViewById(R.id.minitBattery);
        statusbarMinit.setOnMinitBatteryErrorListener(this);

        // round them up for easy maintenance
        mMinitList.add(statusbarMinit);
        mMinitList.add((MinitBattery) systemIconsSuperContainer
                .findViewById(R.id.minitBattery));

        checkEnvironment();
    }

    private void checkEnvironment() {
        try {
            mRM = new ResourceManager(mContext);
            mHasSettings = hasSettingsApp();

            if (mHasSettings || mRM.resourcesExists()) {
                mState = MinitState.ENABLED;
                mSettings = new MinitSettings();
                mSettings.init(mResolver);
                initialize();
                startListening();
            } else {
                hide();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize!");
            e.printStackTrace();
            shutDown();
            mState = MinitState.ERROR;
        }
    }

    private void shutDown() {
        mState = MinitState.DISABLED;
        stopListening();
        hide();
        mRM = null;
        mSettings = null;
    }

    private void initialize() {
        for (MinitBattery minit : mMinitList) {
            minit.init(mContext, mRM, mSettings);
        }
        if (mRM.resourcesExists() || mSettings.hasIconsInStorage()) {
            mState = MinitState.SETUP;
            updateSettings();
        }
    }

    private void updateBattery(int status, int level) {
        for (MinitBattery minit : mMinitList) {
            minit.updateBattery(status, level);
        }
    }

    private void updateSettings() {
        mSettings.getSettings(mResolver, mRM.resourcesExists());
        for (MinitBattery minit : mMinitList) {
            minit.updateSettings(mState, mSettings);
        }
    }

    private void updateImage() {
        for (MinitBattery minit : mMinitList) {
            minit.updateImage();
        }
    }

    private void hide() {
        for (MinitBattery minit : mMinitList) {
            minit.setVisibility(View.GONE);
        }
    }

    private void startListening() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction("com.three.minit.BATTERY_TYPE_CHANGED");
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter);
    }

    private void stopListening() {
        mContext.unregisterReceiver(mReceiver);
    }

    private boolean hasSettingsApp() {
        return isPackageInstalled(mContext, SETTINGS_FREE, true)
                || isPackageInstalled(mContext, SETTINGS_PAID, true);
    }

    private boolean isPackageInstalled(Context context, String pkg,
            boolean ignoreState) {
        if (pkg != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(
                        pkg, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onPackageChanged(String pkg, PackageState state) {
        if (TextUtils.equals(pkg, SETTINGS_FREE)
                || TextUtils.equals(pkg, SETTINGS_PAID)) {
            if (state == PackageState.PACKAGE_ADDED) {
                checkEnvironment();
            } else if (state == PackageState.PACKAGE_REMOVED) {
                mHasSettings = hasSettingsApp(); // remote chance user had free and paid app
                                                 // installed and removed one but kept other
                if (mHasSettings) {
                    checkEnvironment();
                } else {
                    if (mRM.resourcesExists()) {
                        checkEnvironment();
                    } else {
                        shutDown();
                    }
                }
            }
        }
    }

    @Override
    public void onError() {
        // better luck next reboot
        shutDown();
        mState = MinitState.ERROR;
    }
}

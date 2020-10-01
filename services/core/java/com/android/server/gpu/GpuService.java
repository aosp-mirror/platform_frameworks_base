/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.gpu;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.gamedriver.GameDriverProto.Blacklist;
import android.gamedriver.GameDriverProto.Blacklists;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Slog;

import com.android.framework.protobuf.InvalidProtocolBufferException;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Service to manage GPU related features.
 *
 * <p>GPU service is a core service that monitors, coordinates all GPU related features,
 * as well as collect metrics about the GPU and GPU driver.</p>
 */
public class GpuService extends SystemService {
    public static final String TAG = "GpuService";
    public static final boolean DEBUG = false;

    private static final String PROD_DRIVER_PROPERTY = "ro.gfx.driver.0";
    private static final String DEV_DRIVER_PROPERTY = "ro.gfx.driver.1";
    private static final String GAME_DRIVER_WHITELIST_FILENAME = "whitelist.txt";
    private static final int BASE64_FLAGS = Base64.NO_PADDING | Base64.NO_WRAP;

    private final Context mContext;
    private final String mProdDriverPackageName;
    private final String mDevDriverPackageName;
    private final PackageManager mPackageManager;
    private final Object mLock = new Object();
    private final Object mDeviceConfigLock = new Object();
    private final boolean mHasProdDriver;
    private final boolean mHasDevDriver;
    private ContentResolver mContentResolver;
    private long mGameDriverVersionCode;
    private SettingsObserver mSettingsObserver;
    private DeviceConfigListener mDeviceConfigListener;
    @GuardedBy("mLock")
    private Blacklists mBlacklists;

    public GpuService(Context context) {
        super(context);

        mContext = context;
        mProdDriverPackageName = SystemProperties.get(PROD_DRIVER_PROPERTY);
        mGameDriverVersionCode = -1;
        mDevDriverPackageName = SystemProperties.get(DEV_DRIVER_PROPERTY);
        mPackageManager = context.getPackageManager();
        mHasProdDriver = !TextUtils.isEmpty(mProdDriverPackageName);
        mHasDevDriver = !TextUtils.isEmpty(mDevDriverPackageName);
        if (mHasDevDriver || mHasProdDriver) {
            final IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(ACTION_PACKAGE_ADDED);
            packageFilter.addAction(ACTION_PACKAGE_CHANGED);
            packageFilter.addAction(ACTION_PACKAGE_REMOVED);
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL,
                                                packageFilter, null, null);
        }
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            mContentResolver = mContext.getContentResolver();
            if (!mHasProdDriver && !mHasDevDriver) {
                return;
            }
            mSettingsObserver = new SettingsObserver();
            mDeviceConfigListener = new DeviceConfigListener();
            fetchGameDriverPackageProperties();
            processBlacklists();
            setBlacklist();
            fetchDeveloperDriverPackageProperties();
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mGameDriverBlackUri =
                Settings.Global.getUriFor(Settings.Global.GAME_DRIVER_BLACKLISTS);

        SettingsObserver() {
            super(new Handler());
            mContentResolver.registerContentObserver(mGameDriverBlackUri, false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) {
                return;
            }

            if (mGameDriverBlackUri.equals(uri)) {
                processBlacklists();
                setBlacklist();
            }
        }
    }

    private final class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {

        DeviceConfigListener() {
            super();
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_GAME_DRIVER,
                    mContext.getMainExecutor(), this);
        }
        @Override
        public void onPropertiesChanged(Properties properties) {
            synchronized (mDeviceConfigLock) {
                if (properties.getKeyset().contains(Settings.Global.GAME_DRIVER_BLACKLISTS)) {
                    parseBlacklists(
                            properties.getString(Settings.Global.GAME_DRIVER_BLACKLISTS, ""));
                    setBlacklist();
                }
            }
        }
    }

    private final class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final Uri data = intent.getData();
            if (data == null && DEBUG) {
                Slog.e(TAG, "Cannot handle package broadcast with null data");
                return;
            }
            final String packageName = data.getSchemeSpecificPart();
            final boolean isProdDriver = packageName.equals(mProdDriverPackageName);
            final boolean isDevDriver = packageName.equals(mDevDriverPackageName);
            if (!isProdDriver && !isDevDriver) {
                return;
            }

            switch (intent.getAction()) {
                case ACTION_PACKAGE_ADDED:
                case ACTION_PACKAGE_CHANGED:
                case ACTION_PACKAGE_REMOVED:
                    if (isProdDriver) {
                        fetchGameDriverPackageProperties();
                        setBlacklist();
                    } else if (isDevDriver) {
                        fetchDeveloperDriverPackageProperties();
                    }
                    break;
                default:
                    // do nothing
                    break;
            }
        }
    }

    private static void assetToSettingsGlobal(Context context, Context driverContext,
            String fileName, String settingsGlobal, CharSequence delimiter) {
        try {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(driverContext.getAssets().open(fileName)));
            final ArrayList<String> assetStrings = new ArrayList<>();
            for (String assetString; (assetString = reader.readLine()) != null; ) {
                assetStrings.add(assetString);
            }
            Settings.Global.putString(context.getContentResolver(),
                                      settingsGlobal,
                                      String.join(delimiter, assetStrings));
        } catch (IOException e) {
            if (DEBUG) {
                Slog.w(TAG, "Failed to load " + fileName + ", abort.");
            }
        }
    }

    private void fetchGameDriverPackageProperties() {
        final ApplicationInfo driverInfo;
        try {
            driverInfo = mPackageManager.getApplicationInfo(mProdDriverPackageName,
                                                            PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.e(TAG, "driver package '" + mProdDriverPackageName + "' not installed");
            }
            return;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        if (driverInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Slog.w(TAG, "Driver package is not known to be compatible with O");
            }
            return;
        }

        // Reset the whitelist.
        Settings.Global.putString(mContentResolver,
                                  Settings.Global.GAME_DRIVER_WHITELIST, "");
        mGameDriverVersionCode = driverInfo.longVersionCode;

        try {
            final Context driverContext = mContext.createPackageContext(mProdDriverPackageName,
                                                                        Context.CONTEXT_RESTRICTED);

            assetToSettingsGlobal(mContext, driverContext, GAME_DRIVER_WHITELIST_FILENAME,
                    Settings.Global.GAME_DRIVER_WHITELIST, ",");
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.w(TAG, "driver package '" + mProdDriverPackageName + "' not installed");
            }
        }
    }

    private void processBlacklists() {
        String base64String = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_GAME_DRIVER,
                Settings.Global.GAME_DRIVER_BLACKLISTS);
        if (base64String == null) {
            base64String =
                    Settings.Global.getString(mContentResolver,
                                              Settings.Global.GAME_DRIVER_BLACKLISTS);
        }
        parseBlacklists(base64String != null ? base64String : "");
    }

    private void parseBlacklists(String base64String) {
        synchronized (mLock) {
            // Reset all blacklists
            mBlacklists = null;
            try {
                mBlacklists = Blacklists.parseFrom(Base64.decode(base64String, BASE64_FLAGS));
            } catch (IllegalArgumentException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Can't parse blacklist, skip and continue...");
                }
            } catch (InvalidProtocolBufferException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Can't parse blacklist, skip and continue...");
                }
            }
        }
    }

    private void setBlacklist() {
        Settings.Global.putString(mContentResolver,
                                  Settings.Global.GAME_DRIVER_BLACKLIST, "");
        synchronized (mLock) {
            if (mBlacklists == null) {
                return;
            }
            List<Blacklist> blacklists = mBlacklists.getBlacklistsList();
            for (Blacklist blacklist : blacklists) {
                if (blacklist.getVersionCode() == mGameDriverVersionCode) {
                    Settings.Global.putString(mContentResolver,
                            Settings.Global.GAME_DRIVER_BLACKLIST,
                            String.join(",", blacklist.getPackageNamesList()));
                    return;
                }
            }
        }
    }

    private void fetchDeveloperDriverPackageProperties() {
        final ApplicationInfo driverInfo;
        try {
            driverInfo = mPackageManager.getApplicationInfo(mDevDriverPackageName,
                                                            PackageManager.MATCH_SYSTEM_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Slog.e(TAG, "driver package '" + mDevDriverPackageName + "' not installed");
            }
            return;
        }

        // O drivers are restricted to the sphal linker namespace, so don't try to use
        // packages unless they declare they're compatible with that restriction.
        if (driverInfo.targetSdkVersion < Build.VERSION_CODES.O) {
            if (DEBUG) {
                Slog.w(TAG, "Driver package is not known to be compatible with O");
            }
            return;
        }

        setUpdatableDriverPath(driverInfo);
    }

    private void setUpdatableDriverPath(ApplicationInfo ai) {
        if (ai.primaryCpuAbi == null) {
            nSetUpdatableDriverPath("");
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(ai.sourceDir).append("!/lib/");
        nSetUpdatableDriverPath(sb.toString());
    }

    private static native void nSetUpdatableDriverPath(String driverPath);
}

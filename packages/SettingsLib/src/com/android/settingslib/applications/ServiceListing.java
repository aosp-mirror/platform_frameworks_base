/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.applications;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;

import com.android.settingslib.wrapper.PackageManagerWrapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Class for managing services matching a given intent and requesting a given permission.
 */
public class ServiceListing {
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final String mTag;
    private final String mSetting;
    private final String mIntentAction;
    private final String mPermission;
    private final String mNoun;
    private final HashSet<ComponentName> mEnabledServices = new HashSet<>();
    private final List<ServiceInfo> mServices = new ArrayList<>();
    private final List<Callback> mCallbacks = new ArrayList<>();

    private boolean mListening;

    private ServiceListing(Context context, String tag,
            String setting, String intentAction, String permission, String noun) {
        mContentResolver = context.getContentResolver();
        mContext = context;
        mTag = tag;
        mSetting = setting;
        mIntentAction = intentAction;
        mPermission = permission;
        mNoun = noun;
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (mListening) {
            // listen for package changes
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            mContext.registerReceiver(mPackageReceiver, filter);
            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(mSetting),
                    false, mSettingsObserver);
        } else {
            mContext.unregisterReceiver(mPackageReceiver);
            mContentResolver.unregisterContentObserver(mSettingsObserver);
        }
    }

    private void saveEnabledServices() {
        StringBuilder sb = null;
        for (ComponentName cn : mEnabledServices) {
            if (sb == null) {
                sb = new StringBuilder();
            } else {
                sb.append(':');
            }
            sb.append(cn.flattenToString());
        }
        Settings.Secure.putString(mContentResolver, mSetting,
                sb != null ? sb.toString() : "");
    }

    private void loadEnabledServices() {
        mEnabledServices.clear();
        final String flat = Settings.Secure.getString(mContentResolver, mSetting);
        if (flat != null && !"".equals(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    mEnabledServices.add(cn);
                }
            }
        }
    }

    public void reload() {
        loadEnabledServices();
        mServices.clear();
        final int user = ActivityManager.getCurrentUser();

        final PackageManagerWrapper pmWrapper =
                new PackageManagerWrapper(mContext.getPackageManager());
        List<ResolveInfo> installedServices = pmWrapper.queryIntentServicesAsUser(
                new Intent(mIntentAction),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                user);

        for (ResolveInfo resolveInfo : installedServices) {
            ServiceInfo info = resolveInfo.serviceInfo;

            if (!mPermission.equals(info.permission)) {
                Slog.w(mTag, "Skipping " + mNoun + " service "
                        + info.packageName + "/" + info.name
                        + ": it does not require the permission "
                        + mPermission);
                continue;
            }
            mServices.add(info);
        }
        for (Callback callback : mCallbacks) {
            callback.onServicesReloaded(mServices);
        }
    }

    public boolean isEnabled(ComponentName cn) {
        return mEnabledServices.contains(cn);
    }

    public void setEnabled(ComponentName cn, boolean enabled) {
        if (enabled) {
            mEnabledServices.add(cn);
        } else {
            mEnabledServices.remove(cn);
        }
        saveEnabledServices();
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            reload();
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    public interface Callback {
        void onServicesReloaded(List<ServiceInfo> services);
    }

    public static class Builder {
        private final Context mContext;
        private String mTag;
        private String mSetting;
        private String mIntentAction;
        private String mPermission;
        private String mNoun;

        public Builder(Context context) {
            mContext = context;
        }

        public Builder setTag(String tag) {
            mTag = tag;
            return this;
        }

        public Builder setSetting(String setting) {
            mSetting = setting;
            return this;
        }

        public Builder setIntentAction(String intentAction) {
            mIntentAction = intentAction;
            return this;
        }

        public Builder setPermission(String permission) {
            mPermission = permission;
            return this;
        }

        public Builder setNoun(String noun) {
            mNoun = noun;
            return this;
        }

        public ServiceListing build() {
            return new ServiceListing(mContext, mTag, mSetting, mIntentAction, mPermission, mNoun);
        }
    }
}

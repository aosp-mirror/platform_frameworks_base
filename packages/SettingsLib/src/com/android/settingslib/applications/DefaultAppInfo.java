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

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.util.IconDrawableFactory;

import com.android.settingslib.widget.CandidateInfo;

/**
 * Data model representing an app in DefaultAppPicker UI.
 */
public class DefaultAppInfo extends CandidateInfo {

    public final int userId;
    public final ComponentName componentName;
    public final PackageItemInfo packageItemInfo;
    public final String summary;
    protected final PackageManager mPm;
    private final Context mContext;

    public DefaultAppInfo(Context context, PackageManager pm, int uid, ComponentName cn) {
        this(context, pm, uid, cn, null /* summary */, true /* enabled */);
    }

    public DefaultAppInfo(Context context, PackageManager pm, int uid, PackageItemInfo info) {
        this(context, pm, uid, info, null /* summary */, true /* enabled */);
    }

    public DefaultAppInfo(Context context, PackageManager pm, int uid, ComponentName cn,
                          String summary, boolean enabled) {
        super(enabled);
        mContext = context;
        mPm = pm;
        packageItemInfo = null;
        userId = uid;
        componentName = cn;
        this.summary = summary;
    }

    public DefaultAppInfo(Context context, PackageManager pm, int uid, PackageItemInfo info,
                          String summary, boolean enabled) {
        super(enabled);
        mContext = context;
        mPm = pm;
        userId = uid;
        packageItemInfo = info;
        componentName = null;
        this.summary = summary;
    }

    @Override
    public CharSequence loadLabel() {
        if (componentName != null) {
            try {
                final ComponentInfo componentInfo = getComponentInfo();
                if (componentInfo != null) {
                    return componentInfo.loadLabel(mPm);
                } else {
                    final ApplicationInfo appInfo = mPm.getApplicationInfoAsUser(
                            componentName.getPackageName(), 0, userId);
                    return appInfo.loadLabel(mPm);
                }
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        } else if (packageItemInfo != null) {
            return packageItemInfo.loadLabel(mPm);
        } else {
            return null;
        }

    }

    @Override
    public Drawable loadIcon() {
        final IconDrawableFactory factory = IconDrawableFactory.newInstance(mContext);
        if (componentName != null) {
            try {
                final ComponentInfo componentInfo = getComponentInfo();
                final ApplicationInfo appInfo = mPm.getApplicationInfoAsUser(
                        componentName.getPackageName(), 0, userId);
                if (componentInfo != null) {
                    return factory.getBadgedIcon(componentInfo, appInfo, userId);
                } else {
                    return factory.getBadgedIcon(appInfo);
                }
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        if (packageItemInfo != null) {
            try {
                final ApplicationInfo appInfo = mPm.getApplicationInfoAsUser(
                        packageItemInfo.packageName, 0, userId);
                return factory.getBadgedIcon(packageItemInfo, appInfo, userId);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public String getKey() {
        if (componentName != null) {
            return componentName.flattenToString();
        } else if (packageItemInfo != null) {
            return packageItemInfo.packageName;
        } else {
            return null;
        }
    }

    private ComponentInfo getComponentInfo() {
        try {
            ComponentInfo componentInfo = AppGlobals.getPackageManager().getActivityInfo(
                    componentName, 0, userId);
            if (componentInfo == null) {
                componentInfo = AppGlobals.getPackageManager().getServiceInfo(
                        componentName, 0, userId);
            }
            return componentInfo;
        } catch (RemoteException e) {
            return null;
        }
    }
}

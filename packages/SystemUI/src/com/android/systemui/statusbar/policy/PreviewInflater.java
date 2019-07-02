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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.statusbar.phone.KeyguardPreviewContainer;

import java.util.List;

/**
 * Utility class to inflate previews for phone and camera affordance.
 */
public class PreviewInflater {

    private static final String TAG = "PreviewInflater";

    private static final String META_DATA_KEYGUARD_LAYOUT = "com.android.keyguard.layout";
    private final ActivityIntentHelper mActivityIntentHelper;

    private Context mContext;
    private LockPatternUtils mLockPatternUtils;

    public PreviewInflater(Context context, LockPatternUtils lockPatternUtils,
            ActivityIntentHelper activityIntentHelper) {
        mContext = context;
        mLockPatternUtils = lockPatternUtils;
        mActivityIntentHelper = activityIntentHelper;
    }

    public View inflatePreview(Intent intent) {
        WidgetInfo info = getWidgetInfo(intent);
        return inflatePreview(info);
    }

    public View inflatePreviewFromService(ComponentName componentName) {
        WidgetInfo info = getWidgetInfoFromService(componentName);
        return inflatePreview(info);
    }

    private KeyguardPreviewContainer inflatePreview(WidgetInfo info) {
        if (info == null) {
            return null;
        }
        View v = inflateWidgetView(info);
        if (v == null) {
            return null;
        }
        KeyguardPreviewContainer container = new KeyguardPreviewContainer(mContext, null);
        container.addView(v);
        return container;
    }

    private View inflateWidgetView(WidgetInfo widgetInfo) {
        View widgetView = null;
        try {
            Context appContext = mContext.createPackageContext(
                    widgetInfo.contextPackage, Context.CONTEXT_RESTRICTED);
            LayoutInflater appInflater = (LayoutInflater)
                    appContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            appInflater = appInflater.cloneInContext(appContext);
            widgetView = appInflater.inflate(widgetInfo.layoutId, null, false);
        } catch (PackageManager.NameNotFoundException|RuntimeException e) {
            Log.w(TAG, "Error creating widget view", e);
        }
        return widgetView;
    }

    private WidgetInfo getWidgetInfoFromService(ComponentName componentName) {
        PackageManager packageManager = mContext.getPackageManager();
        // Look for the preview specified in the service meta-data
        try {
            Bundle metaData = packageManager.getServiceInfo(
                    componentName, PackageManager.GET_META_DATA).metaData;
            return getWidgetInfoFromMetaData(componentName.getPackageName(), metaData);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to load preview; " + componentName.flattenToShortString()
                    + " not found", e);
        }
        return null;
    }

    private WidgetInfo getWidgetInfoFromMetaData(String contextPackage,
            Bundle metaData) {
        if (metaData == null) {
            return null;
        }
        int layoutId = metaData.getInt(META_DATA_KEYGUARD_LAYOUT);
        if (layoutId == 0) {
            return null;
        }
        WidgetInfo info = new WidgetInfo();
        info.contextPackage = contextPackage;
        info.layoutId = layoutId;
        return info;
    }

    private WidgetInfo getWidgetInfo(Intent intent) {
        PackageManager packageManager = mContext.getPackageManager();
        int flags = PackageManager.MATCH_DEFAULT_ONLY
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        final List<ResolveInfo> appList = packageManager.queryIntentActivitiesAsUser(
                intent, flags, KeyguardUpdateMonitor.getCurrentUser());
        if (appList.size() == 0) {
            return null;
        }
        ResolveInfo resolved = packageManager.resolveActivityAsUser(intent,
                flags | PackageManager.GET_META_DATA,
                KeyguardUpdateMonitor.getCurrentUser());
        if (mActivityIntentHelper.wouldLaunchResolverActivity(resolved, appList)) {
            return null;
        }
        if (resolved == null || resolved.activityInfo == null) {
            return null;
        }
        return getWidgetInfoFromMetaData(resolved.activityInfo.packageName,
                resolved.activityInfo.metaData);
    }

    private static class WidgetInfo {
        String contextPackage;
        int layoutId;
    }
}

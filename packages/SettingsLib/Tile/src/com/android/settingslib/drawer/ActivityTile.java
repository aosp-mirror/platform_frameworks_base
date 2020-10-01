/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.drawer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Description of a single dashboard tile which is generated from an activity.
 */
public class ActivityTile extends Tile {
    private static final String TAG = "ActivityTile";

    public ActivityTile(ActivityInfo info, String category) {
        super(info, category);
        setMetaData(info.metaData);
    }

    ActivityTile(Parcel in) {
        super(in);
    }

    @Override
    public int getId() {
        return Objects.hash(getPackageName(), getComponentName());
    }

    @Override
    public String getDescription() {
        return getPackageName() + "/" + getComponentName();
    }

    @Override
    protected ComponentInfo getComponentInfo(Context context) {
        if (mComponentInfo == null) {
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            final Intent intent = getIntent();
            final List<ResolveInfo> infoList =
                    pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            if (infoList != null && !infoList.isEmpty()) {
                mComponentInfo = infoList.get(0).activityInfo;
                setMetaData(mComponentInfo.metaData);
            } else {
                Log.e(TAG, "Cannot find package info for "
                        + intent.getComponent().flattenToString());
            }
        }
        return mComponentInfo;
    }

    @Override
    protected CharSequence getComponentLabel(Context context) {
        final PackageManager pm = context.getPackageManager();
        final ComponentInfo info = getComponentInfo(context);
        return info == null
                ? null
                : info.loadLabel(pm);
    }

    @Override
    protected int getComponentIcon(ComponentInfo componentInfo) {
        return componentInfo.icon;
    }
}

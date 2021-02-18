/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.FeatureFlags;

import javax.inject.Inject;

/**
 * Enables People Space widgets.
 */
@SysUISingleton
public class PeopleSpaceWidgetEnabler extends SystemUI {
    private static final String TAG = "PeopleSpaceWdgtEnabler";
    private Context mContext;
    private FeatureFlags mFeatureFlags;

    @Inject
    public PeopleSpaceWidgetEnabler(Context context, FeatureFlags featureFlags) {
        super(context);
        mContext = context;
        mFeatureFlags = featureFlags;
    }

    @Override
    public void start() {
        Log.d(TAG, "Starting service");
        try {
            boolean showPeopleSpace = mFeatureFlags.isPeopleTileEnabled();
            mContext.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class),
                    showPeopleSpace
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "Error enabling People Space widget:", e);
        }
    }
}

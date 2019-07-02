/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

class AppList<APP extends AppInfo> {
    private ImmutableList<APP> mAllApps;
    private ImmutableList<APP> mRegularApps;
    private ImmutableMap<SpecialApp,APP> mSpecialApps;

    private AppList() {
    }

    public ImmutableList<APP> getAllApps() {
        return mAllApps;
    }

    public ImmutableList<APP> getRegularApps() {
        return mRegularApps;
    }

    public List<APP> findApp(String pkg) {
        List<APP> result = new ArrayList();
        for (APP app: mRegularApps) {
            if (app.hasPackage(pkg)) {
                result.add(app);
            }
        }
        return result;
    }

    public APP findApp(SpecialApp specialApp) {
        return mSpecialApps.get(specialApp);
    }

    public static class Builder<APP extends AppInfo, BUILDER extends AppInfo.Builder<APP>> {
        private final HashMap<AttributionKey,BUILDER> mApps = new HashMap();

        public Builder() {
        }

        public AppList<APP> build() {
            final AppList<APP> result = new AppList();
            final ArrayList<APP> allApps = new ArrayList();
            final ArrayList<APP> regularApps = new ArrayList();
            final HashMap<SpecialApp,APP> specialApps = new HashMap();
            for (AppInfo.Builder<APP> app: mApps.values()) {
                final AttributionKey attribution = app.getAttribution();
                final APP appActivity = app.build();
                allApps.add(appActivity);
                if (attribution.isSpecialApp()) {
                    specialApps.put(attribution.getSpecialApp(), appActivity);
                } else {
                    regularApps.add(appActivity);
                }
            }
            result.mAllApps = ImmutableList.copyOf(allApps);
            result.mRegularApps = ImmutableList.copyOf(regularApps);
            result.mSpecialApps = ImmutableMap.copyOf(specialApps);
            return result;
        }

        public BUILDER get(AttributionKey attribution) {
            return mApps.get(attribution);
        }

        public BUILDER put(AttributionKey attribution, BUILDER app) {
            return mApps.put(attribution, app);
        }
    }
}

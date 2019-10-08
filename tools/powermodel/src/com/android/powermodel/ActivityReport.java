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

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;

/**
 * ActivityReport contains the summary of the activity that consumes power
 * as reported by batterystats or statsd.
 */
public class ActivityReport {
    private AppList<AppActivity> mApps;

    public ImmutableList<AppActivity> getAllApps() {
        return mApps.getAllApps();
    }

    public ImmutableList<AppActivity> getRegularApps() {
        return mApps.getRegularApps();
    }

    public List<AppActivity> findApp(String pkg) {
        return mApps.findApp(pkg);
    }

    public AppActivity findApp(SpecialApp specialApp) {
        return mApps.findApp(specialApp);
    }

    /**
     * Find a component in the GLOBAL app.
     * <p>
     * Returns null if either the global app doesn't exist (bad data?) or the component
     * doesn't exist in the global app.
     */
    public ComponentActivity findGlobalComponent(Component component) {
         final AppActivity global = mApps.findApp(SpecialApp.GLOBAL);
         if (global == null) {
             return null;
         }
         return global.getComponentActivity(component);
    }

    public static class Builder {
        private AppList.Builder<AppActivity,AppActivity.Builder> mApps = new AppList.Builder();

        public Builder() {
        }

        public ActivityReport build() {
            final ActivityReport result = new ActivityReport();
            result.mApps = mApps.build();
            return result;
        }

        public void addActivity(Component component, Collection<ComponentActivity> activities) {
            for (final ComponentActivity activity: activities) {
                addActivity(component, activity);
            }
        }

        public void addActivity(Component component, ComponentActivity activity) {
            AppActivity.Builder app = mApps.get(activity.attribution);
            if (app == null) {
                app = new AppActivity.Builder();
                app.setAttribution(activity.attribution);
                mApps.put(activity.attribution, app);
            }
            app.addComponentActivity(component, activity);
        }
    }
}

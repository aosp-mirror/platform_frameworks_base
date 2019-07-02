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
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

/**
 * PowerReport contains the summary of all power used on a device
 * as reported by batterystats or statsd, based on the power profile.
 */
public class PowerReport {
    private AppList<AppPower> mApps;
    private double mTotalPowerMah;

    private PowerReport() {
    }

    /**
     * The total power used by this device for this PowerReport.
     */
    public double getTotalPowerMah() {
        return mTotalPowerMah;
    }

    public List<AppPower> getAllApps() {
        return mApps.getAllApps();
    }

    public List<AppPower> getRegularApps() {
        return mApps.getRegularApps();
    }

    public List<AppPower> findApp(String pkg) {
        return mApps.findApp(pkg);
    }

    public AppPower findApp(SpecialApp specialApp) {
        return mApps.findApp(specialApp);
    }

    public static PowerReport createReport(PowerProfile profile, ActivityReport activityReport) {
        final PowerReport.Builder powerReport = new PowerReport.Builder();
        for (final AppActivity appActivity: activityReport.getAllApps()) {
            final AppPower.Builder appPower = new AppPower.Builder();
            appPower.setAttribution(appActivity.getAttribution());

            for (final ImmutableMap.Entry<Component,ComponentActivity> entry:
                    appActivity.getComponentActivities().entrySet()) {
                final ComponentPower componentPower = entry.getValue()
                        .applyProfile(activityReport, profile);
                if (componentPower != null) {
                    appPower.addComponentPower(entry.getKey(), componentPower);
                }
            }

            powerReport.add(appPower);
        }
        return powerReport.build();
    }

    private static class Builder {
        private AppList.Builder mApps = new AppList.Builder();

        public Builder() {
        }

        public PowerReport build() {
            final PowerReport report = new PowerReport();

            report.mApps = mApps.build();

            for (AppPower app: report.mApps.getAllApps()) {
                report.mTotalPowerMah += app.getAppPowerMah();
            }

            return report;
        }

        public void add(AppPower.Builder app) {
            mApps.put(app.getAttribution(), app);
        }
    }
}

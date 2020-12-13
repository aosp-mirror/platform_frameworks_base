/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.batterystatsviewer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import androidx.annotation.NonNull;

import com.android.internal.os.BatterySipper;

import java.util.Locale;

class BatteryConsumerInfoHelper {

    private static final String SYSTEM_SERVER_PACKAGE_NAME = "android";

    public static class BatteryConsumerInfo {
        public String id;
        public CharSequence label;
        public double powerMah;
        public ApplicationInfo iconInfo;
        public CharSequence packages;
        public CharSequence details;
    }

    @NonNull
    public static BatteryConsumerInfo makeBatteryConsumerInfo(PackageManager packageManager,
            @NonNull BatterySipper sipper) {
        BatteryConsumerInfo info = new BatteryConsumerInfo();
        info.id = BatteryConsumerData.batteryConsumerId(sipper);
        sipper.sumPower();
        info.powerMah = sipper.totalSmearedPowerMah;
        switch (sipper.drainType) {
            case APP: {
                int uid = sipper.getUid();
                info.details = String.format("UID: %d", uid);
                String packageWithHighestDrain = sipper.packageWithHighestDrain;
                if (uid == Process.ROOT_UID) {
                    info.label = "<root>";
                } else {
                    String[] packages = packageManager.getPackagesForUid(uid);
                    String primaryPackageName = null;
                    if (uid == Process.SYSTEM_UID) {
                        primaryPackageName = SYSTEM_SERVER_PACKAGE_NAME;
                    } else if (packages != null) {
                        for (String name : packages) {
                            primaryPackageName = name;
                            if (name.equals(packageWithHighestDrain)) {
                                break;
                            }
                        }
                    }

                    if (primaryPackageName != null) {
                        try {
                            ApplicationInfo applicationInfo =
                                    packageManager.getApplicationInfo(primaryPackageName, 0);
                            info.label = applicationInfo.loadLabel(packageManager);
                            info.iconInfo = applicationInfo;
                        } catch (PackageManager.NameNotFoundException e) {
                            info.label = primaryPackageName;
                        }
                    } else if (packageWithHighestDrain != null) {
                        info.label = packageWithHighestDrain;
                    }

                    if (packages != null && packages.length > 0) {
                        StringBuilder sb = new StringBuilder();
                        if (primaryPackageName != null) {
                            sb.append(primaryPackageName);
                        }
                        for (String packageName : packages) {
                            if (packageName.equals(primaryPackageName)) {
                                continue;
                            }

                            if (sb.length() != 0) {
                                sb.append(", ");
                            }
                            sb.append(packageName);
                        }

                        info.packages = sb;
                    }
                }
                break;
            }
            case USER:
                info.label = "User";
                info.details = String.format(Locale.getDefault(), "User ID: %d", sipper.userId);
                break;
            case AMBIENT_DISPLAY:
                info.label = "Ambient display";
                break;
            case BLUETOOTH:
                info.label = "Bluetooth";
                break;
            case CAMERA:
                info.label = "Camera";
                break;
            case CELL:
                info.label = "Cell";
                break;
            case FLASHLIGHT:
                info.label = "Flashlight";
                break;
            case IDLE:
                info.label = "Idle";
                break;
            case MEMORY:
                info.label = "Memory";
                break;
            case OVERCOUNTED:
                info.label = "Overcounted";
                break;
            case PHONE:
                info.label = "Phone";
                break;
            case SCREEN:
                info.label = "Screen";
                break;
            case UNACCOUNTED:
                info.label = "Unaccounted";
                break;
            case WIFI:
                info.label = "WiFi";
                break;
        }
        // Default the app icon to System Server. This includes root, dex2oat and other UIDs.
        if (info.iconInfo == null) {
            try {
                info.iconInfo =
                        packageManager.getApplicationInfo(SYSTEM_SERVER_PACKAGE_NAME, 0);
            } catch (PackageManager.NameNotFoundException nameNotFoundException) {
                // Won't happen
            }
        }
        return info;
    }
}

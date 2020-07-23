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

package com.android.frameworks.core.powerstatsviewer;

import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import com.android.internal.os.BatterySipper;

class AppInfoHelper {

    private static final String SYSTEM_SERVER_PACKAGE_NAME = "android";

    public static class AppInfo {
        public int uid;
        public CharSequence label;
        public double powerMah;
        public ApplicationInfo iconInfo;
        public CharSequence packages;
    }

    @Nullable
    public static AppInfo makeApplicationInfo(PackageManager packageManager, int uid,
            @Nullable BatterySipper sipper) {
        if (sipper != null && sipper.drainType != BatterySipper.DrainType.APP) {
            return null;
        }

        String packageWithHighestDrain = null;

        AppInfo info = new AppInfo();
        info.uid = uid;
        if (sipper != null) {
            sipper.sumPower();
            info.powerMah = sipper.totalSmearedPowerMah;
            packageWithHighestDrain = sipper.packageWithHighestDrain;
        }
        if (info.uid == Process.ROOT_UID) {
            info.label = "<root>";
        } else {
            String[] packages = packageManager.getPackagesForUid(info.uid);
            String primaryPackageName = null;
            if (info.uid == Process.SYSTEM_UID) {
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

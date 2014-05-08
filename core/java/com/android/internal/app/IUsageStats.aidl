/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import android.app.UsageStats;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.ParcelableParcel;

interface IUsageStats {
    void noteResumeComponent(in ComponentName componentName);
    void notePauseComponent(in ComponentName componentName);
    void noteLaunchTime(in ComponentName componentName, int millis);
    void noteStartConfig(in Configuration config);
    UsageStats.PackageStats getPkgUsageStats(String callingPkg, in ComponentName componentName);
    UsageStats.PackageStats[] getAllPkgUsageStats(String callingPkg);
    ParcelableParcel getCurrentStats(String callingPkg);
}

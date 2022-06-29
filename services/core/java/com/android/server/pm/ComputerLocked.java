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

package com.android.server.pm;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This subclass is the external interface to the live computer.  Some internal helper
 * methods are overridden to fetch live data instead of snapshot data.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
public final class ComputerLocked extends ComputerEngine {

    ComputerLocked(PackageManagerService.Snapshot args) {
        super(args, -1);
    }

    protected ComponentName resolveComponentName() {
        return mService.getResolveComponentName();
    }
    protected ActivityInfo instantAppInstallerActivity() {
        return mService.mInstantAppInstallerActivity;
    }
    protected ApplicationInfo androidApplication() {
        return mService.getCoreAndroidApplication();
    }
}

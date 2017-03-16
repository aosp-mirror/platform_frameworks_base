/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.os.RemoteException;
import android.util.Slog;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.VMRuntime;

import java.util.List;

/**
 * A dex load reporter which will notify package manager of any dex file loaded
 * with {@code BaseDexClassLoader}.
 * The goals are:
 *     1) discover secondary dex files so that they can be optimized during the
 *        idle maintenance job.
 *     2) determine whether or not a dex file is used by an app which does not
 *        own it (in order to select the optimal compilation method).
 * @hide
 */
/*package*/ class DexLoadReporter implements BaseDexClassLoader.Reporter {
    private static final String TAG = "DexLoadReporter";

    /*package*/ static final DexLoadReporter INSTANCE = new DexLoadReporter();

    private DexLoadReporter() {}

    @Override
    public void report(List<String> dexPaths) {
        if (dexPaths.isEmpty()) {
            return;
        }
        String packageName = ActivityThread.currentPackageName();
        try {
            ActivityThread.getPackageManager().notifyDexLoad(
                    packageName, dexPaths, VMRuntime.getRuntime().vmInstructionSet());
        } catch (RemoteException re) {
            Slog.e(TAG, "Failed to notify PM about dex load for package " + packageName, re);
        }
    }
}

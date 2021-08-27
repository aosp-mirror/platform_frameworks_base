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

import android.os.FileUtils;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private static final DexLoadReporter INSTANCE = new DexLoadReporter();

    private static final boolean DEBUG = false;

    // We must guard the access to the list of data directories because
    // we might have concurrent accesses. Apps might load dex files while
    // new data dirs are registered (due to creation of LoadedApks via
    // create createApplicationContext).
    @GuardedBy("mDataDirs")
    private final Set<String> mDataDirs;

    private DexLoadReporter() {
        mDataDirs = new HashSet<>();
    }

    /*package*/ static DexLoadReporter getInstance() {
        return INSTANCE;
    }

    /**
     * Register an application data directory with the reporter.
     * The data directories are used to determine if a dex file is secondary dex or not.
     * Note that this method may be called multiple times for the same app, registering
     * different data directories. This may happen when apps share the same user id
     * ({@code android:sharedUserId}). For example, if app1 and app2 share the same user
     * id, and app1 loads app2 apk, then both data directories will be registered.
     */
    /*package*/ void registerAppDataDir(String packageName, String dataDir) {
        if (DEBUG) {
            Slog.i(TAG, "Package " + packageName + " registering data dir: " + dataDir);
        }
        // TODO(calin): A few code paths imply that the data dir
        // might be null. Investigate when that can happen.
        if (dataDir != null) {
            synchronized (mDataDirs) {
                mDataDirs.add(dataDir);
            }
        }
    }

    @Override
    public void report(Map<String, String> classLoaderContextMap) {
        if (classLoaderContextMap.isEmpty()) {
            Slog.wtf(TAG, "Bad call to DexLoadReporter: empty classLoaderContextMap");
            return;
        }

        // Notify the package manager about the dex loads unconditionally.
        // The load might be for either a primary or secondary dex file.
        notifyPackageManager(classLoaderContextMap);
        // Check for secondary dex files and register them for profiling if possible.
        // Note that we only register the dex paths belonging to the first class loader.
        registerSecondaryDexForProfiling(classLoaderContextMap.keySet());
    }

    private void notifyPackageManager(Map<String, String> classLoaderContextMap) {
        // Get the class loader names for the binder call.
        String packageName = ActivityThread.currentPackageName();
        try {
            ActivityThread.getPackageManager().notifyDexLoad(packageName,
                    classLoaderContextMap, VMRuntime.getRuntime().vmInstructionSet());
        } catch (RemoteException re) {
            Slog.e(TAG, "Failed to notify PM about dex load for package " + packageName, re);
        }
    }

    private void registerSecondaryDexForProfiling(Set<String> dexPaths) {
        if (!SystemProperties.getBoolean("dalvik.vm.dexopt.secondary", false)) {
            return;
        }
        // Make a copy of the current data directories so that we don't keep the lock
        // while registering for profiling. The registration will perform I/O to
        // check for or create the profile.
        String[] dataDirs;
        synchronized (mDataDirs) {
            dataDirs = mDataDirs.toArray(new String[0]);
        }
        for (String dexPath : dexPaths) {
            registerSecondaryDexForProfiling(dexPath, dataDirs);
        }
    }

    private void registerSecondaryDexForProfiling(String dexPath, String[] dataDirs) {
        if (!isSecondaryDexFile(dexPath, dataDirs)) {
            // The dex path is not a secondary dex file. Nothing to do.
            return;
        }

        // Secondary dex profiles are stored in the oat directory, next to dex file
        // and have the same name with 'cur.prof' appended.
        // NOTE: Keep this in sync with installd expectations.
        File dexPathFile = new File(dexPath);
        File secondaryProfileDir = new File(dexPathFile.getParent(), "oat");
        File secondaryCurProfile =
                new File(secondaryProfileDir, dexPathFile.getName() + ".cur.prof");
        File secondaryRefProfile = new File(secondaryProfileDir, dexPathFile.getName() + ".prof");

        // Create the profile if not already there.
        // Returns true if the file was created, false if the file already exists.
        // or throws exceptions in case of errors.
        if (!secondaryProfileDir.exists()) {
            if (!secondaryProfileDir.mkdir()) {
                Slog.e(TAG, "Could not create the profile directory: " + secondaryCurProfile);
                // Do not continue with registration if we could not create the oat dir.
                return;
            }
        }

        try {
            boolean created = secondaryCurProfile.createNewFile();
            if (DEBUG && created) {
                Slog.i(TAG, "Created profile for secondary dex: " + secondaryCurProfile);
            }
        } catch (IOException ex) {
            Slog.e(TAG, "Failed to create profile for secondary dex " + dexPath
                    + ":" + ex.getMessage());
            // Do not continue with registration if we could not create the profile files.
            return;
        }

        // If we got here, the dex paths is a secondary dex and we were able to create the profile.
        // Register the path to the runtime.
        VMRuntime.registerAppInfo(
                ActivityThread.currentPackageName(),
                secondaryCurProfile.getPath(),
                secondaryRefProfile.getPath(),
                new String[] { dexPath },
                VMRuntime.CODE_PATH_TYPE_SECONDARY_DEX);
    }

    // A dex file is a secondary dex file if it is in any of the registered app
    // data directories.
    private boolean isSecondaryDexFile(String dexPath, String[] dataDirs) {
        for (String dataDir : dataDirs) {
            if (FileUtils.contains(dataDir, dexPath)) {
                return true;
            }
        }
        return false;
    }
}

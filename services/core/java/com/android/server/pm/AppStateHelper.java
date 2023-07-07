/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A helper class to provide queries for app states concerning gentle-update.
 */
public class AppStateHelper {
    // The duration to monitor network usage to determine if network is active or not
    private static final long ACTIVE_NETWORK_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final Context mContext;

    public AppStateHelper(Context context) {
        mContext = context;
    }

    /**
     * True if the package is loaded into the process.
     */
    private static boolean isPackageLoaded(RunningAppProcessInfo info, String packageName) {
        return ArrayUtils.contains(info.pkgList, packageName)
                || ArrayUtils.contains(info.pkgDeps, packageName);
    }

    /**
     * Returns the importance of the given package.
     */
    private int getImportance(String packageName) {
        var am = mContext.getSystemService(ActivityManager.class);
        return am.getPackageImportance(packageName);
    }

    /**
     * True if the app owns the audio focus.
     */
    private boolean hasAudioFocus(String packageName) {
        var audioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        try {
            var focusInfos = audioService.getFocusStack();
            int size = focusInfos.size();
            var audioFocusPackage = (size > 0) ? focusInfos.get(size - 1).getPackageName() : null;
            return TextUtils.equals(packageName, audioFocusPackage);
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * True if any app is using voice communication.
     */
    private boolean hasVoiceCall() {
        var am = mContext.getSystemService(AudioManager.class);
        try {
            int audioMode = am.getMode();
            return audioMode == AudioManager.MODE_IN_CALL
                    || audioMode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * True if the app is recording audio.
     */
    private boolean isRecordingAudio(String packageName) {
        var am = mContext.getSystemService(AudioManager.class);
        try {
            for (var arc : am.getActiveRecordingConfigurations()) {
                if (TextUtils.equals(arc.getClientPackageName(), packageName)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * True if the app is in the foreground.
     */
    private boolean isAppForeground(String packageName) {
        return getImportance(packageName) <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
    }

    /**
     * True if the app is currently at the top of the screen that the user is interacting with.
     */
    public boolean isAppTopVisible(String packageName) {
        return getImportance(packageName) <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * True if the app is playing/recording audio.
     */
    private boolean hasActiveAudio(String packageName) {
        return hasAudioFocus(packageName) || isRecordingAudio(packageName);
    }

    private boolean hasActiveNetwork(List<String> packageNames, int networkType) {
        var pm = ActivityThread.getPackageManager();
        var nsm = mContext.getSystemService(NetworkStatsManager.class);
        var endTime = System.currentTimeMillis();
        var startTime = endTime - ACTIVE_NETWORK_DURATION_MILLIS;
        try (var stats = nsm.querySummary(networkType, null, startTime, endTime)) {
            var bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                var packageName = pm.getNameForUid(bucket.getUid());
                if (!packageNames.contains(packageName)) {
                    continue;
                }
                if (bucket.getRxPackets() > 0 || bucket.getTxPackets() > 0) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * True if {@code arr} contains any element in {@code which}.
     * Both {@code arr} and {@code which} must be sorted in advance.
     */
    private static boolean containsAny(String[] arr, List<String> which) {
        int s1 = arr.length;
        int s2 = which.size();
        for (int i = 0, j = 0; i < s1 && j < s2; ) {
            int val = arr[i].compareTo(which.get(j));
            if (val == 0) {
                return true;
            } else if (val < 0) {
                ++i;
            } else {
                ++j;
            }
        }
        return false;
    }

    private void addLibraryDependency(ArraySet<String> results, List<String> libPackageNames) {
        var pmInternal = LocalServices.getService(PackageManagerInternal.class);

        var libraryNames = new ArrayList<String>();
        var staticSharedLibraryNames = new ArrayList<String>();
        var sdkLibraryNames = new ArrayList<String>();
        for (var packageName : libPackageNames) {
            var pkg = pmInternal.getAndroidPackage(packageName);
            if (pkg == null) {
                continue;
            }
            libraryNames.addAll(pkg.getLibraryNames());
            var libraryName = pkg.getStaticSharedLibraryName();
            if (libraryName != null) {
                staticSharedLibraryNames.add(libraryName);
            }
            libraryName = pkg.getSdkLibraryName();
            if (libraryName != null) {
                sdkLibraryNames.add(libraryName);
            }
        }

        if (libraryNames.isEmpty()
                && staticSharedLibraryNames.isEmpty()
                && sdkLibraryNames.isEmpty()) {
            return;
        }

        Collections.sort(libraryNames);
        Collections.sort(sdkLibraryNames);
        Collections.sort(staticSharedLibraryNames);

        pmInternal.forEachPackageState(pkgState -> {
            var pkg = pkgState.getPkg();
            if (pkg == null) {
                return;
            }
            if (containsAny(pkg.getUsesLibrariesSorted(), libraryNames)
                    || containsAny(pkg.getUsesOptionalLibrariesSorted(), libraryNames)
                    || containsAny(pkg.getUsesStaticLibrariesSorted(), staticSharedLibraryNames)
                    || containsAny(pkg.getUsesSdkLibrariesSorted(), sdkLibraryNames)) {
                results.add(pkg.getPackageName());
            }
        });
    }

    /**
     * True if any app has sent or received network data over the past
     * {@link #ACTIVE_NETWORK_DURATION_MILLIS} milliseconds.
     */
    private boolean hasActiveNetwork(List<String> packageNames) {
        return hasActiveNetwork(packageNames, ConnectivityManager.TYPE_WIFI)
                || hasActiveNetwork(packageNames, ConnectivityManager.TYPE_MOBILE);
    }

    /**
     * True if any app is interacting with the user.
     */
    public boolean hasInteractingApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (hasActiveAudio(packageName)
                    || isAppTopVisible(packageName)) {
                return true;
            }
        }
        return hasActiveNetwork(packageNames);
    }

    /**
     * True if any app is in the foreground.
     */
    public boolean hasForegroundApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (isAppForeground(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if any app is top visible.
     */
    public boolean hasTopVisibleApp(List<String> packageNames) {
        for (var packageName : packageNames) {
            if (isAppTopVisible(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if there is an ongoing phone call.
     */
    public boolean isInCall() {
        // Simulate in-call during test
        if (SystemProperties.getBoolean(
                "debug.pm.gentle_update_test.is_in_call", false)) {
            return true;
        }
        // TelecomManager doesn't handle the case where some apps don't implement ConnectionService.
        // We check apps using voice communication to detect if the device is in call.
        var tm = mContext.getSystemService(TelecomManager.class);
        return tm.isInCall() || hasVoiceCall();
    }

    /**
     * Returns a list of packages which depend on {@code packageNames}. These are the packages
     * that will be affected when updating {@code packageNames} and should participate in
     * the evaluation of install constraints.
     */
    public List<String> getDependencyPackages(List<String> packageNames) {
        var results = new ArraySet<String>();
        // Include packages sharing the same process
        var am = mContext.getSystemService(ActivityManager.class);
        for (var info : am.getRunningAppProcesses()) {
            for (var packageName : packageNames) {
                if (!isPackageLoaded(info, packageName)) {
                    continue;
                }
                for (var pkg : info.pkgList) {
                    results.add(pkg);
                }
            }
        }
        // Include packages using bounded services
        var amInternal = LocalServices.getService(ActivityManagerInternal.class);
        for (var packageName : packageNames) {
            results.addAll(amInternal.getClientPackages(packageName));
        }
        // Include packages using libraries
        addLibraryDependency(results, packageNames);

        return new ArrayList<>(results);
    }
}

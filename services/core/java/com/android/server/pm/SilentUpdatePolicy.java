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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.util.concurrent.TimeUnit;

/**
 * Tracks for the installer package name and installing app package name of silent updates.
 * This class is used to throttle repeated silent updates of the same installer and application
 * in the {@link PackageInstallerSession}.
 */
public class SilentUpdatePolicy {
    // The default throttle time to prevent the installer from silently updating the same app
    // repeatedly.
    private static final long SILENT_UPDATE_THROTTLE_TIME_MS = TimeUnit.SECONDS.toMillis(30);

    // Map to the uptime timestamp for each installer and app of the silent update.
    @GuardedBy("mSilentUpdateInfos")
    private final ArrayMap<Pair<String, String>, Long> mSilentUpdateInfos = new ArrayMap<>();

    // An installer allowed for the unlimited silent updates within the throttle time
    @GuardedBy("mSilentUpdateInfos")
    private String mAllowUnlimitedSilentUpdatesInstaller;

    @GuardedBy("mSilentUpdateInfos")
    private long mSilentUpdateThrottleTimeMs = SILENT_UPDATE_THROTTLE_TIME_MS;

    /**
     * Checks if the silent update is allowed by the given installer and app package name.
     *
     * @param installerPackageName The installer package name to check
     * @param packageName The package name which is installing
     * @return true if the silent update is allowed.
     */
    public boolean isSilentUpdateAllowed(@Nullable String installerPackageName,
            @NonNull String packageName) {
        if (installerPackageName == null) {
            // Always true for the installer from Shell
            return true;
        }
        final long lastSilentUpdatedMs = getTimestampMs(installerPackageName, packageName);
        final long throttleTimeMs;
        synchronized (mSilentUpdateInfos) {
            throttleTimeMs = mSilentUpdateThrottleTimeMs;
        }
        return SystemClock.uptimeMillis() - lastSilentUpdatedMs > throttleTimeMs;
    }

    /**
     * Adding track for the installer package name and installing app of a silent update. This is
     * used to determine whether a silent update is allowed.
     *
     * @param installerPackageName The installer package name
     * @param packageName The package name which is installing
     */
    public void track(@Nullable String installerPackageName, @NonNull String packageName) {
        if (installerPackageName == null) {
            // No need to track the installer from Shell.
            return;
        }
        synchronized (mSilentUpdateInfos) {
            if (mAllowUnlimitedSilentUpdatesInstaller != null
                    && mAllowUnlimitedSilentUpdatesInstaller.equals(installerPackageName)) {
                return;
            }
            final long uptime = SystemClock.uptimeMillis();
            pruneLocked(uptime);

            final Pair<String, String> key = Pair.create(installerPackageName, packageName);
            mSilentUpdateInfos.put(key, uptime);
        }
    }

    /**
     * Set an installer to allow for the unlimited silent updates. Reset the tracker if the
     * installer package name is <code>null</code>.
     */
    void setAllowUnlimitedSilentUpdates(@Nullable String installerPackageName) {
        synchronized (mSilentUpdateInfos) {
            if (installerPackageName == null) {
                mSilentUpdateInfos.clear();
            }
            mAllowUnlimitedSilentUpdatesInstaller = installerPackageName;
        }
    }

    /**
     * Set the silent updates throttle time in seconds.
     *
     * @param throttleTimeInSeconds The throttle time to set, or <code>-1</code> to restore the
     *        value to the default.
     */
    void setSilentUpdatesThrottleTime(long throttleTimeInSeconds) {
        synchronized (mSilentUpdateInfos) {
            mSilentUpdateThrottleTimeMs = throttleTimeInSeconds >= 0
                    ? TimeUnit.SECONDS.toMillis(throttleTimeInSeconds)
                    : SILENT_UPDATE_THROTTLE_TIME_MS;
        }
    }

    private void pruneLocked(long uptime) {
        final int size = mSilentUpdateInfos.size();
        for (int i = size - 1; i >= 0; i--) {
            final long lastSilentUpdatedMs = mSilentUpdateInfos.valueAt(i);
            if (uptime - lastSilentUpdatedMs > mSilentUpdateThrottleTimeMs) {
                mSilentUpdateInfos.removeAt(i);
            }
        }
    }

    /**
     * Get the timestamp by the given installer and app package name. {@code -1} is returned if not
     * exist.
     */
    private long getTimestampMs(@NonNull String installerPackageName, @NonNull String packageName) {
        final Pair<String, String> key = Pair.create(installerPackageName, packageName);
        final Long timestampMs;
        synchronized (mSilentUpdateInfos) {
            timestampMs = mSilentUpdateInfos.get(key);
        }
        return timestampMs != null ? timestampMs : -1;
    }

    void dump(IndentingPrintWriter pw) {
        synchronized (mSilentUpdateInfos) {
            if (mSilentUpdateInfos.isEmpty()) {
                return;
            }
            pw.println("Last silent updated Infos:");
            pw.increaseIndent();
            final int size = mSilentUpdateInfos.size();
            for (int i = 0; i < size; i++) {
                final Pair<String, String> key = mSilentUpdateInfos.keyAt(i);
                if (key == null) {
                    continue;
                }
                pw.printPair("installerPackageName", key.first);
                pw.printPair("packageName", key.second);
                pw.printPair("silentUpdatedMillis", mSilentUpdateInfos.valueAt(i));
                pw.println();
            }
            pw.decreaseIndent();
        }
    }
}

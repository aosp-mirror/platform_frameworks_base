/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Keeps track of recent active state changes in apps.
 * Access should be guarded by a lock by the caller.
 */
public class AppIdleHistory {

    private static final String TAG = "AppIdleHistory";

    // History for all users and all packages
    private SparseArray<ArrayMap<String,PackageHistory>> mIdleHistory = new SparseArray<>();
    private long mLastPeriod = 0;
    private static final long ONE_MINUTE = 60 * 1000;
    private static final int HISTORY_SIZE = 100;
    private static final int FLAG_LAST_STATE = 2;
    private static final int FLAG_PARTIAL_ACTIVE = 1;
    private static final long PERIOD_DURATION = UsageStatsService.COMPRESS_TIME ? ONE_MINUTE
            : 60 * ONE_MINUTE;

    @VisibleForTesting
    static final String APP_IDLE_FILENAME = "app_idle_stats.xml";
    private static final String TAG_PACKAGES = "packages";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";
    // Screen on timebase time when app was last used
    private static final String ATTR_SCREEN_IDLE = "screenIdleTime";
    // Elapsed timebase time when app was last used
    private static final String ATTR_ELAPSED_IDLE = "elapsedIdleTime";

    // device on time = mElapsedDuration + (timeNow - mElapsedSnapshot)
    private long mElapsedSnapshot; // Elapsed time snapshot when last write of mDeviceOnDuration
    private long mElapsedDuration; // Total device on duration since device was "born"

    // screen on time = mScreenOnDuration + (timeNow - mScreenOnSnapshot)
    private long mScreenOnSnapshot; // Elapsed time snapshot when last write of mScreenOnDuration
    private long mScreenOnDuration; // Total screen on duration since device was "born"

    private long mElapsedTimeThreshold;
    private long mScreenOnTimeThreshold;
    private final File mStorageDir;

    private boolean mScreenOn;

    private static class PackageHistory {
        final byte[] recent = new byte[HISTORY_SIZE];
        long lastUsedElapsedTime;
        long lastUsedScreenTime;
    }

    AppIdleHistory(long elapsedRealtime) {
        this(Environment.getDataSystemDirectory(), elapsedRealtime);
    }

    @VisibleForTesting
    AppIdleHistory(File storageDir, long elapsedRealtime) {
        mElapsedSnapshot = elapsedRealtime;
        mScreenOnSnapshot = elapsedRealtime;
        mStorageDir = storageDir;
        readScreenOnTimeLocked();
    }

    public void setThresholds(long elapsedTimeThreshold, long screenOnTimeThreshold) {
        mElapsedTimeThreshold = elapsedTimeThreshold;
        mScreenOnTimeThreshold = screenOnTimeThreshold;
    }

    public void updateDisplayLocked(boolean screenOn, long elapsedRealtime) {
        if (screenOn == mScreenOn) return;

        mScreenOn = screenOn;
        if (mScreenOn) {
            mScreenOnSnapshot = elapsedRealtime;
        } else {
            mScreenOnDuration += elapsedRealtime - mScreenOnSnapshot;
            mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
            writeScreenOnTimeLocked();
            mElapsedSnapshot = elapsedRealtime;
        }
    }

    public long getScreenOnTimeLocked(long elapsedRealtime) {
        long screenOnTime = mScreenOnDuration;
        if (mScreenOn) {
            screenOnTime += elapsedRealtime - mScreenOnSnapshot;
        }
        return screenOnTime;
    }

    @VisibleForTesting
    File getScreenOnTimeFile() {
        return new File(mStorageDir, "screen_on_time");
    }

    private void readScreenOnTimeLocked() {
        File screenOnTimeFile = getScreenOnTimeFile();
        if (screenOnTimeFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(screenOnTimeFile));
                mScreenOnDuration = Long.parseLong(reader.readLine());
                mElapsedDuration = Long.parseLong(reader.readLine());
                reader.close();
            } catch (IOException | NumberFormatException e) {
            }
        } else {
            writeScreenOnTimeLocked();
        }
    }

    private void writeScreenOnTimeLocked() {
        AtomicFile screenOnTimeFile = new AtomicFile(getScreenOnTimeFile());
        FileOutputStream fos = null;
        try {
            fos = screenOnTimeFile.startWrite();
            fos.write((Long.toString(mScreenOnDuration) + "\n"
                    + Long.toString(mElapsedDuration) + "\n").getBytes());
            screenOnTimeFile.finishWrite(fos);
        } catch (IOException ioe) {
            screenOnTimeFile.failWrite(fos);
        }
    }

    /**
     * To be called periodically to keep track of elapsed time when app idle times are written
     */
    public void writeElapsedTimeLocked() {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        // Only bump up and snapshot the elapsed time. Don't change screen on duration.
        mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
        mElapsedSnapshot = elapsedRealtime;
        writeScreenOnTimeLocked();
    }

    public void reportUsageLocked(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, PackageHistory> userHistory = getUserHistoryLocked(userId);
        PackageHistory packageHistory = getPackageHistoryLocked(userHistory, packageName,
                elapsedRealtime);

        shiftHistoryToNow(userHistory, elapsedRealtime);

        packageHistory.lastUsedElapsedTime = mElapsedDuration
                + (elapsedRealtime - mElapsedSnapshot);
        packageHistory.lastUsedScreenTime = getScreenOnTimeLocked(elapsedRealtime);
        packageHistory.recent[HISTORY_SIZE - 1] = FLAG_LAST_STATE | FLAG_PARTIAL_ACTIVE;
    }

    public void setIdle(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, PackageHistory> userHistory = getUserHistoryLocked(userId);
        PackageHistory packageHistory = getPackageHistoryLocked(userHistory, packageName,
                elapsedRealtime);

        shiftHistoryToNow(userHistory, elapsedRealtime);

        packageHistory.recent[HISTORY_SIZE - 1] &= ~FLAG_LAST_STATE;
    }

    private void shiftHistoryToNow(ArrayMap<String, PackageHistory> userHistory,
            long elapsedRealtime) {
        long thisPeriod = elapsedRealtime / PERIOD_DURATION;
        // Has the period switched over? Slide all users' package histories
        if (mLastPeriod != 0 && mLastPeriod < thisPeriod
                && (thisPeriod - mLastPeriod) < HISTORY_SIZE - 1) {
            int diff = (int) (thisPeriod - mLastPeriod);
            final int NUSERS = mIdleHistory.size();
            for (int u = 0; u < NUSERS; u++) {
                userHistory = mIdleHistory.valueAt(u);
                for (PackageHistory idleState : userHistory.values()) {
                    // Shift left
                    System.arraycopy(idleState.recent, diff, idleState.recent, 0,
                            HISTORY_SIZE - diff);
                    // Replicate last state across the diff
                    for (int i = 0; i < diff; i++) {
                        idleState.recent[HISTORY_SIZE - i - 1] =
                            (byte) (idleState.recent[HISTORY_SIZE - diff - 1] & FLAG_LAST_STATE);
                    }
                }
            }
        }
        mLastPeriod = thisPeriod;
    }

    private ArrayMap<String, PackageHistory> getUserHistoryLocked(int userId) {
        ArrayMap<String, PackageHistory> userHistory = mIdleHistory.get(userId);
        if (userHistory == null) {
            userHistory = new ArrayMap<>();
            mIdleHistory.put(userId, userHistory);
            readAppIdleTimesLocked(userId, userHistory);
        }
        return userHistory;
    }

    private PackageHistory getPackageHistoryLocked(ArrayMap<String, PackageHistory> userHistory,
            String packageName, long elapsedRealtime) {
        PackageHistory packageHistory = userHistory.get(packageName);
        if (packageHistory == null) {
            packageHistory = new PackageHistory();
            packageHistory.lastUsedElapsedTime = getElapsedTimeLocked(elapsedRealtime);
            packageHistory.lastUsedScreenTime = getScreenOnTimeLocked(elapsedRealtime);
            userHistory.put(packageName, packageHistory);
        }
        return packageHistory;
    }

    public void onUserRemoved(int userId) {
        mIdleHistory.remove(userId);
    }

    public boolean isIdleLocked(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, PackageHistory> userHistory = getUserHistoryLocked(userId);
        PackageHistory packageHistory =
                getPackageHistoryLocked(userHistory, packageName, elapsedRealtime);
        if (packageHistory == null) {
            return false; // Default to not idle
        } else {
            return hasPassedThresholdsLocked(packageHistory, elapsedRealtime);
        }
    }

    private long getElapsedTimeLocked(long elapsedRealtime) {
        return (elapsedRealtime - mElapsedSnapshot + mElapsedDuration);
    }

    public void setIdleLocked(String packageName, int userId, boolean idle, long elapsedRealtime) {
        ArrayMap<String, PackageHistory> userHistory = getUserHistoryLocked(userId);
        PackageHistory packageHistory = getPackageHistoryLocked(userHistory, packageName,
                elapsedRealtime);
        packageHistory.lastUsedElapsedTime = getElapsedTimeLocked(elapsedRealtime)
                - mElapsedTimeThreshold;
        packageHistory.lastUsedScreenTime = getScreenOnTimeLocked(elapsedRealtime)
                - (idle ? mScreenOnTimeThreshold : 0) - 1000 /* just a second more */;
    }

    public void clearUsageLocked(String packageName, int userId) {
        ArrayMap<String, PackageHistory> userHistory = getUserHistoryLocked(userId);
        userHistory.remove(packageName);
    }

    private boolean hasPassedThresholdsLocked(PackageHistory packageHistory, long elapsedRealtime) {
        return (packageHistory.lastUsedScreenTime
                    <= getScreenOnTimeLocked(elapsedRealtime) - mScreenOnTimeThreshold)
                && (packageHistory.lastUsedElapsedTime
                        <= getElapsedTimeLocked(elapsedRealtime) - mElapsedTimeThreshold);
    }

    private File getUserFile(int userId) {
        return new File(new File(new File(mStorageDir, "users"),
                Integer.toString(userId)), APP_IDLE_FILENAME);
    }

    private void readAppIdleTimesLocked(int userId, ArrayMap<String, PackageHistory> userHistory) {
        FileInputStream fis = null;
        try {
            AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
            fis = appIdleFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Skip
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(TAG, "Unable to read app idle file for user " + userId);
                return;
            }
            if (!parser.getName().equals(TAG_PACKAGES)) {
                return;
            }
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_PACKAGE)) {
                        final String packageName = parser.getAttributeValue(null, ATTR_NAME);
                        PackageHistory packageHistory = new PackageHistory();
                        packageHistory.lastUsedElapsedTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_ELAPSED_IDLE));
                        packageHistory.lastUsedScreenTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_SCREEN_IDLE));
                        userHistory.put(packageName, packageHistory);
                    }
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Unable to read app idle file for user " + userId);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    public void writeAppIdleTimesLocked(int userId) {
        FileOutputStream fos = null;
        AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
        try {
            fos = appIdleFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(bos, StandardCharsets.UTF_8.name());
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            xml.startTag(null, TAG_PACKAGES);

            ArrayMap<String,PackageHistory> userHistory = getUserHistoryLocked(userId);
            final int N = userHistory.size();
            for (int i = 0; i < N; i++) {
                String packageName = userHistory.keyAt(i);
                PackageHistory history = userHistory.valueAt(i);
                xml.startTag(null, TAG_PACKAGE);
                xml.attribute(null, ATTR_NAME, packageName);
                xml.attribute(null, ATTR_ELAPSED_IDLE,
                        Long.toString(history.lastUsedElapsedTime));
                xml.attribute(null, ATTR_SCREEN_IDLE,
                        Long.toString(history.lastUsedScreenTime));
                xml.endTag(null, TAG_PACKAGE);
            }

            xml.endTag(null, TAG_PACKAGES);
            xml.endDocument();
            appIdleFile.finishWrite(fos);
        } catch (Exception e) {
            appIdleFile.failWrite(fos);
            Slog.e(TAG, "Error writing app idle file for user " + userId);
        }
    }

    public void dump(IndentingPrintWriter idpw, int userId) {
        idpw.println("Package idle stats:");
        idpw.increaseIndent();
        ArrayMap<String, PackageHistory> userHistory = mIdleHistory.get(userId);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long totalElapsedTime = getElapsedTimeLocked(elapsedRealtime);
        final long screenOnTime = getScreenOnTimeLocked(elapsedRealtime);
        if (userHistory == null) return;
        final int P = userHistory.size();
        for (int p = 0; p < P; p++) {
            final String packageName = userHistory.keyAt(p);
            final PackageHistory packageHistory = userHistory.valueAt(p);
            idpw.print("package=" + packageName);
            idpw.print(" lastUsedElapsed=");
            TimeUtils.formatDuration(totalElapsedTime - packageHistory.lastUsedElapsedTime, idpw);
            idpw.print(" lastUsedScreenOn=");
            TimeUtils.formatDuration(screenOnTime - packageHistory.lastUsedScreenTime, idpw);
            idpw.print(" idle=" + (isIdleLocked(packageName, userId, elapsedRealtime) ? "y" : "n"));
            idpw.println();
        }
        idpw.println();
        idpw.print("totalElapsedTime=");
        TimeUtils.formatDuration(getElapsedTimeLocked(elapsedRealtime), idpw);
        idpw.println();
        idpw.print("totalScreenOnTime=");
        TimeUtils.formatDuration(getScreenOnTimeLocked(elapsedRealtime), idpw);
        idpw.println();
        idpw.decreaseIndent();
    }

    public void dumpHistory(IndentingPrintWriter idpw, int userId) {
        ArrayMap<String, PackageHistory> userHistory = mIdleHistory.get(userId);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        if (userHistory == null) return;
        final int P = userHistory.size();
        for (int p = 0; p < P; p++) {
            final String packageName = userHistory.keyAt(p);
            final byte[] history = userHistory.valueAt(p).recent;
            for (int i = 0; i < HISTORY_SIZE; i++) {
                idpw.print(history[i] == 0 ? '.' : 'A');
            }
            idpw.print(" idle=" + (isIdleLocked(packageName, userId, elapsedRealtime) ? "y" : "n"));
            idpw.print("  " + packageName);
            idpw.println();
        }
    }
}

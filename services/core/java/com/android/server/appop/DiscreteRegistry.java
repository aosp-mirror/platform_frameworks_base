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

package com.android.server.appop;

import static android.app.AppOpsManager.FILTER_BY_ATTRIBUTION_TAG;
import static android.app.AppOpsManager.FILTER_BY_OP_NAMES;
import static android.app.AppOpsManager.FILTER_BY_PACKAGE_NAME;
import static android.app.AppOpsManager.FILTER_BY_UID;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.flagsToString;
import static android.app.AppOpsManager.getUidStateName;

import static java.lang.Long.min;
import static java.lang.Math.max;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * This class manages information about recent accesses to ops for
 * permission usage timeline.
 *
 * The timeline history is kept for limited time (initial default is 24 hours) and
 * discarded after that.
 *
 * Every time state is saved (default is 30 minutes), memory state is dumped to a
 * new file and memory state is cleared. Files older than time limit are deleted
 * during the process.
 *
 * When request comes in, files are read and requested information is collected
 * and delivered.
 */

final class DiscreteRegistry {
    static final String TIMELINE_FILE_SUFFIX = "tl";
    private static final String TAG = DiscreteRegistry.class.getSimpleName();

    private static final String PROPERTY_DISCRETE_HISTORY_CUTOFF = "discrete_history_cutoff_millis";
    private static final String PROPERTY_DISCRETE_HISTORY_QUANTIZATION =
            "discrete_history_quantization_millis";
    private static final long DEFAULT_DISCRETE_HISTORY_CUTOFF = Duration.ofHours(24).toMillis();
    private static final long MAXIMUM_DISCRETE_HISTORY_CUTOFF = Duration.ofDays(30).toMillis();
    private static final long DEFAULT_DISCRETE_HISTORY_QUANTIZATION =
            Duration.ofMinutes(1).toMillis();

    private static long sDiscreteHistoryCutoff;
    private static long sDiscreteHistoryQuantization;

    private static final String TAG_HISTORY = "h";
    private static final String ATTR_VERSION = "v";
    private static final int CURRENT_VERSION = 1;

    private static final String TAG_UID = "u";
    private static final String ATTR_UID = "ui";

    private static final String TAG_PACKAGE = "p";
    private static final String ATTR_PACKAGE_NAME = "pn";

    private static final String TAG_OP = "o";
    private static final String ATTR_OP_ID = "op";

    private static final String TAG_TAG = "a";
    private static final String ATTR_TAG = "at";

    private static final String TAG_ENTRY = "e";
    private static final String ATTR_NOTE_TIME = "nt";
    private static final String ATTR_NOTE_DURATION = "nd";
    private static final String ATTR_UID_STATE = "us";
    private static final String ATTR_FLAGS = "f";

    private static final int OP_FLAGS_DISCRETE = OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED;

    // Lock for read/write access to on disk state
    private final Object mOnDiskLock = new Object();

    //Lock for read/write access to in memory state
    private final @NonNull Object mInMemoryLock;

    @GuardedBy("mOnDiskLock")
    private File mDiscreteAccessDir;

    @GuardedBy("mInMemoryLock")
    private DiscreteOps mDiscreteOps;

    @GuardedBy("mOnDiskLock")
    private DiscreteOps mCachedOps = null;

    DiscreteRegistry(Object inMemoryLock) {
        mInMemoryLock = inMemoryLock;
    }

    void systemReady() {
        synchronized (mOnDiskLock) {
            mDiscreteAccessDir = new File(new File(Environment.getDataSystemDirectory(), "appops"),
                    "discrete");
            createDiscreteAccessDirLocked();
            mDiscreteOps = new DiscreteOps();
        }
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                AsyncTask.THREAD_POOL_EXECUTOR, (DeviceConfig.Properties p) -> {
                    setDiscreteHistoryParameters(p);
                });
        sDiscreteHistoryCutoff = DeviceConfig.getLong(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_DISCRETE_HISTORY_CUTOFF, DEFAULT_DISCRETE_HISTORY_CUTOFF);
        sDiscreteHistoryQuantization = DeviceConfig.getLong(DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_DISCRETE_HISTORY_QUANTIZATION, DEFAULT_DISCRETE_HISTORY_QUANTIZATION);
    }

    private void setDiscreteHistoryParameters(DeviceConfig.Properties p) {
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_CUTOFF)) {
            sDiscreteHistoryCutoff = p.getLong(PROPERTY_DISCRETE_HISTORY_CUTOFF,
                    DEFAULT_DISCRETE_HISTORY_CUTOFF);
            if (!Build.IS_DEBUGGABLE) {
                sDiscreteHistoryCutoff = min(MAXIMUM_DISCRETE_HISTORY_CUTOFF,
                        sDiscreteHistoryCutoff);
            }
        }
        if (p.getKeyset().contains(PROPERTY_DISCRETE_HISTORY_QUANTIZATION)) {
            sDiscreteHistoryQuantization = p.getLong(PROPERTY_DISCRETE_HISTORY_QUANTIZATION,
                    DEFAULT_DISCRETE_HISTORY_QUANTIZATION);
            if (!Build.IS_DEBUGGABLE) {
                sDiscreteHistoryQuantization = max(DEFAULT_DISCRETE_HISTORY_QUANTIZATION,
                        sDiscreteHistoryQuantization);
            }
        }
    }

    private void createDiscreteAccessDir() {
        if (!mDiscreteAccessDir.exists()) {
            if (!mDiscreteAccessDir.mkdirs()) {
                Slog.e(TAG, "Failed to create DiscreteRegistry directory");
            }
            FileUtils.setPermissions(mDiscreteAccessDir.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
        }
    }

    /* can be called only after HistoricalRegistry.isPersistenceInitialized() check */
    void recordDiscreteAccess(int uid, String packageName, int op, @Nullable String attributionTag,
            @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState, long accessTime,
            long accessDuration) {
        if (!isDiscreteOp(op, uid, flags)) {
            return;
        }
        synchronized (mInMemoryLock) {
            mDiscreteOps.addDiscreteAccess(op, uid, packageName, attributionTag, flags, uidState,
                    accessTime, accessDuration);
        }
    }

    void writeAndClearAccessHistory() {
        synchronized (mOnDiskLock) {
            if (mDiscreteAccessDir == null) {
                Slog.e(TAG, "State not saved - persistence not initialized.");
                return;
            }
            final File[] files = mDiscreteAccessDir.listFiles();
            if (files != null && files.length > 0) {
                for (File f : files) {
                    final String fileName = f.getName();
                    if (!fileName.endsWith(TIMELINE_FILE_SUFFIX)) {
                        continue;
                    }
                    try {
                        long timestamp = Long.valueOf(fileName.substring(0,
                                fileName.length() - TIMELINE_FILE_SUFFIX.length()));
                        if (Instant.now().minus(sDiscreteHistoryCutoff,
                                ChronoUnit.MILLIS).toEpochMilli() > timestamp) {
                            f.delete();
                            Slog.e(TAG, "Deleting file " + fileName);

                        }
                    } catch (Throwable t) {
                        Slog.e(TAG, "Error while cleaning timeline files: " + t.getMessage() + " "
                                + t.getStackTrace());
                    }
                }
            }
            DiscreteOps discreteOps;
            synchronized (mInMemoryLock) {
                discreteOps = mDiscreteOps;
                mDiscreteOps = new DiscreteOps();
                mCachedOps = null;
            }
            if (discreteOps.isEmpty()) {
                return;
            }
            long currentTimeStamp = Instant.now().toEpochMilli();
            try {
                final File file = new File(mDiscreteAccessDir,
                        currentTimeStamp + TIMELINE_FILE_SUFFIX);
                discreteOps.writeToFile(file);
            } catch (Throwable t) {
                Slog.e(TAG,
                        "Error writing timeline state: " + t.getMessage() + " "
                                + Arrays.toString(t.getStackTrace()));
            }
        }
    }

    void getHistoricalDiscreteOps(AppOpsManager.HistoricalOps result, long beginTimeMillis,
            long endTimeMillis, @AppOpsManager.HistoricalOpsRequestFilter int filter, int uidFilter,
            @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
            @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter) {
        DiscreteOps discreteOps = getAndCacheDiscreteOps();
        discreteOps.filter(beginTimeMillis, endTimeMillis, filter, uidFilter, packageNameFilter,
                opNamesFilter, attributionTagFilter, flagsFilter);
        discreteOps.applyToHistoricalOps(result);
        return;
    }

    private DiscreteOps getAndCacheDiscreteOps() {
        DiscreteOps discreteOps = new DiscreteOps();

        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                discreteOps.merge(mDiscreteOps);
            }
            if (mCachedOps == null) {
                mCachedOps = new DiscreteOps();
                readDiscreteOpsFromDisk(mCachedOps);
            }
            discreteOps.merge(mCachedOps);
        }
        return discreteOps;
    }

    private void readDiscreteOpsFromDisk(DiscreteOps discreteOps) {
        synchronized (mOnDiskLock) {
            long beginTimeMillis = Instant.now().minus(sDiscreteHistoryCutoff,
                    ChronoUnit.MILLIS).toEpochMilli();

            final File[] files = mDiscreteAccessDir.listFiles();
            if (files != null && files.length > 0) {
                for (File f : files) {
                    final String fileName = f.getName();
                    if (!fileName.endsWith(TIMELINE_FILE_SUFFIX)) {
                        continue;
                    }
                    long timestamp = Long.valueOf(fileName.substring(0,
                            fileName.length() - TIMELINE_FILE_SUFFIX.length()));
                    if (timestamp < beginTimeMillis) {
                        continue;
                    }
                    discreteOps.readFromFile(f, beginTimeMillis);
                }
            }
        }
    }

    void clearHistory() {
        synchronized (mOnDiskLock) {
            synchronized (mInMemoryLock) {
                mDiscreteOps = new DiscreteOps();
            }
            FileUtils.deleteContentsAndDir(mDiscreteAccessDir);
            createDiscreteAccessDir();
        }
    }

    void dump(@NonNull PrintWriter pw, int uidFilter, @Nullable String packageNameFilter,
            @Nullable String attributionTagFilter,
            @AppOpsManager.HistoricalOpsRequestFilter int filter, int dumpOp,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix,
            int nDiscreteOps) {
        DiscreteOps discreteOps = getAndCacheDiscreteOps();
        String[] opNamesFilter = dumpOp == OP_NONE ? null
                : new String[]{AppOpsManager.opToPublicName(dumpOp)};
        discreteOps.filter(0, Instant.now().toEpochMilli(), filter, uidFilter, packageNameFilter,
                opNamesFilter, attributionTagFilter, OP_FLAGS_ALL);
        discreteOps.dump(pw, sdf, date, prefix, nDiscreteOps);
    }

    public static boolean isDiscreteOp(int op, int uid, @AppOpsManager.OpFlags int flags) {
        if (!isDiscreteOp(op)) {
            return false;
        }
        if (!isDiscreteUid(uid)) {
            return false;
        }
        if ((flags & (OP_FLAGS_DISCRETE)) == 0) {
            return false;
        }
        return true;
    }

    static boolean isDiscreteOp(int op) {
        if (op != OP_CAMERA && op != OP_RECORD_AUDIO && op != OP_FINE_LOCATION
                && op != OP_COARSE_LOCATION) {
            return false;
        }
        return true;
    }

    static boolean isDiscreteUid(int uid) {
        if (uid < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        return true;
    }

    private final class DiscreteOps {
        ArrayMap<Integer, DiscreteUidOps> mUids;

        DiscreteOps() {
            mUids = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mUids.isEmpty();
        }

        void merge(DiscreteOps other) {
            int nUids = other.mUids.size();
            for (int i = 0; i < nUids; i++) {
                int uid = other.mUids.keyAt(i);
                DiscreteUidOps uidOps = other.mUids.valueAt(i);
                getOrCreateDiscreteUidOps(uid).merge(uidOps);
            }
        }

        void addDiscreteAccess(int op, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @AppOpsManager.OpFlags int flags,
                @AppOpsManager.UidState int uidState, long accessTime, long accessDuration) {
            getOrCreateDiscreteUidOps(uid).addDiscreteAccess(op, packageName, attributionTag, flags,
                    uidState, accessTime, accessDuration);
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter, int uidFilter,
                @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter) {
            if ((filter & FILTER_BY_UID) != 0) {
                ArrayMap<Integer, DiscreteUidOps> uids = new ArrayMap<>();
                uids.put(uidFilter, getOrCreateDiscreteUidOps(uidFilter));
                mUids = uids;
            }
            int nUids = mUids.size();
            for (int i = nUids - 1; i >= 0; i--) {
                mUids.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter, packageNameFilter,
                        opNamesFilter, attributionTagFilter, flagsFilter);
                if (mUids.valueAt(i).isEmpty()) {
                    mUids.removeAt(i);
                }
            }
        }

        private void applyToHistoricalOps(AppOpsManager.HistoricalOps result) {
            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                mUids.valueAt(i).applyToHistory(result, mUids.keyAt(i));
            }
        }

        private void writeToFile(File f) throws Exception {
            FileOutputStream stream = new FileOutputStream(f);
            TypedXmlSerializer out = Xml.resolveSerializer(stream);

            out.startDocument(null, true);
            out.startTag(null, TAG_HISTORY);
            out.attributeInt(null, ATTR_VERSION, CURRENT_VERSION);

            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                out.startTag(null, TAG_UID);
                out.attributeInt(null, ATTR_UID, mUids.keyAt(i));
                mUids.valueAt(i).serialize(out);
                out.endTag(null, TAG_UID);
            }
            out.endTag(null, TAG_HISTORY);
            out.endDocument();
            stream.close();
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nUids = mUids.size();
            for (int i = 0; i < nUids; i++) {
                pw.print(prefix);
                pw.print("Uid: ");
                pw.print(mUids.keyAt(i));
                pw.println();
                mUids.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        private DiscreteUidOps getOrCreateDiscreteUidOps(int uid) {
            DiscreteUidOps result = mUids.get(uid);
            if (result == null) {
                result = new DiscreteUidOps();
                mUids.put(uid, result);
            }
            return result;
        }

        private void readFromFile(File f, long beginTimeMillis) {
            try {
                FileInputStream stream = new FileInputStream(f);
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                XmlUtils.beginDocument(parser, TAG_HISTORY);

                // We haven't released version 1 and have more detailed
                // accounting - just nuke the current state
                final int version = parser.getAttributeInt(null, ATTR_VERSION);
                if (version != CURRENT_VERSION) {
                    throw new IllegalStateException("Dropping unsupported discrete history " + f);
                }

                int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    if (TAG_UID.equals(parser.getName())) {
                        int uid = parser.getAttributeInt(null, ATTR_UID, -1);
                        getOrCreateDiscreteUidOps(uid).deserialize(parser, beginTimeMillis);
                    }
                }
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to read file " + f.getName() + " " + t.getMessage() + " "
                        + Arrays.toString(t.getStackTrace()));
            }

        }
    }

    private void createDiscreteAccessDirLocked() {
        if (!mDiscreteAccessDir.exists()) {
            if (!mDiscreteAccessDir.mkdirs()) {
                Slog.e(TAG, "Failed to create DiscreteRegistry directory");
            }
            FileUtils.setPermissions(mDiscreteAccessDir.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
        }
    }

    private final class DiscreteUidOps {
        ArrayMap<String, DiscretePackageOps> mPackages;

        DiscreteUidOps() {
            mPackages = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mPackages.isEmpty();
        }

        void merge(DiscreteUidOps other) {
            int nPackages = other.mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                String packageName = other.mPackages.keyAt(i);
                DiscretePackageOps p = other.mPackages.valueAt(i);
                getOrCreateDiscretePackageOps(packageName).merge(p);
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String packageNameFilter, @Nullable String[] opNamesFilter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter) {
            if ((filter & FILTER_BY_PACKAGE_NAME) != 0) {
                ArrayMap<String, DiscretePackageOps> packages = new ArrayMap<>();
                packages.put(packageNameFilter, getOrCreateDiscretePackageOps(packageNameFilter));
                mPackages = packages;
            }
            int nPackages = mPackages.size();
            for (int i = nPackages - 1; i >= 0; i--) {
                mPackages.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter, opNamesFilter,
                        attributionTagFilter, flagsFilter);
                if (mPackages.valueAt(i).isEmpty()) {
                    mPackages.removeAt(i);
                }
            }
        }

        void addDiscreteAccess(int op, @NonNull String packageName, @Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration) {
            getOrCreateDiscretePackageOps(packageName).addDiscreteAccess(op, attributionTag, flags,
                    uidState, accessTime, accessDuration);
        }

        private DiscretePackageOps getOrCreateDiscretePackageOps(String packageName) {
            DiscretePackageOps result = mPackages.get(packageName);
            if (result == null) {
                result = new DiscretePackageOps();
                mPackages.put(packageName, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid) {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                mPackages.valueAt(i).applyToHistory(result, uid, mPackages.keyAt(i));
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                out.startTag(null, TAG_PACKAGE);
                out.attribute(null, ATTR_PACKAGE_NAME, mPackages.keyAt(i));
                mPackages.valueAt(i).serialize(out);
                out.endTag(null, TAG_PACKAGE);
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nPackages = mPackages.size();
            for (int i = 0; i < nPackages; i++) {
                pw.print(prefix);
                pw.print("Package: ");
                pw.print(mPackages.keyAt(i));
                pw.println();
                mPackages.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_PACKAGE.equals(parser.getName())) {
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    getOrCreateDiscretePackageOps(packageName).deserialize(parser, beginTimeMillis);
                }
            }
        }
    }

    private final class DiscretePackageOps {
        ArrayMap<Integer, DiscreteOp> mPackageOps;

        DiscretePackageOps() {
            mPackageOps = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mPackageOps.isEmpty();
        }

        void addDiscreteAccess(int op, @Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration) {
            getOrCreateDiscreteOp(op).addDiscreteAccess(attributionTag, flags, uidState, accessTime,
                    accessDuration);
        }

        void merge(DiscretePackageOps other) {
            int nOps = other.mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                int opId = other.mPackageOps.keyAt(i);
                DiscreteOp op = other.mPackageOps.valueAt(i);
                getOrCreateDiscreteOp(opId).merge(op);
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String[] opNamesFilter, @Nullable String attributionTagFilter,
                @AppOpsManager.OpFlags int flagsFilter) {
            int nOps = mPackageOps.size();
            for (int i = nOps - 1; i >= 0; i--) {
                int opId = mPackageOps.keyAt(i);
                if ((filter & FILTER_BY_OP_NAMES) != 0 && !ArrayUtils.contains(opNamesFilter,
                        AppOpsManager.opToPublicName(opId))) {
                    mPackageOps.removeAt(i);
                    continue;
                }
                mPackageOps.valueAt(i).filter(beginTimeMillis, endTimeMillis, filter,
                        attributionTagFilter, flagsFilter);
                if (mPackageOps.valueAt(i).isEmpty()) {
                    mPackageOps.removeAt(i);
                }
            }
        }

        private DiscreteOp getOrCreateDiscreteOp(int op) {
            DiscreteOp result = mPackageOps.get(op);
            if (result == null) {
                result = new DiscreteOp();
                mPackageOps.put(op, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid,
                @NonNull String packageName) {
            int nPackageOps = mPackageOps.size();
            for (int i = 0; i < nPackageOps; i++) {
                mPackageOps.valueAt(i).applyToHistory(result, uid, packageName,
                        mPackageOps.keyAt(i));
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nOps = mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                out.startTag(null, TAG_OP);
                out.attributeInt(null, ATTR_OP_ID, mPackageOps.keyAt(i));
                mPackageOps.valueAt(i).serialize(out);
                out.endTag(null, TAG_OP);
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nOps = mPackageOps.size();
            for (int i = 0; i < nOps; i++) {
                pw.print(prefix);
                pw.print(AppOpsManager.opToName(mPackageOps.keyAt(i)));
                pw.println();
                mPackageOps.valueAt(i).dump(pw, sdf, date, prefix + "  ", nDiscreteOps);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (TAG_OP.equals(parser.getName())) {
                    int op = parser.getAttributeInt(null, ATTR_OP_ID);
                    getOrCreateDiscreteOp(op).deserialize(parser, beginTimeMillis);
                }
            }
        }
    }

    private final class DiscreteOp {
        ArrayMap<String, List<DiscreteOpEvent>> mAttributedOps;

        DiscreteOp() {
            mAttributedOps = new ArrayMap<>();
        }

        boolean isEmpty() {
            return mAttributedOps.isEmpty();
        }

        void merge(DiscreteOp other) {
            int nTags = other.mAttributedOps.size();
            for (int i = 0; i < nTags; i++) {
                String tag = other.mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> otherEvents = other.mAttributedOps.valueAt(i);
                List<DiscreteOpEvent> events = getOrCreateDiscreteOpEventsList(tag);
                mAttributedOps.put(tag, stableListMerge(events, otherEvents));
            }
        }

        private void filter(long beginTimeMillis, long endTimeMillis,
                @AppOpsManager.HistoricalOpsRequestFilter int filter,
                @Nullable String attributionTagFilter, @AppOpsManager.OpFlags int flagsFilter) {
            if ((filter & FILTER_BY_ATTRIBUTION_TAG) != 0) {
                ArrayMap<String, List<DiscreteOpEvent>> attributedOps = new ArrayMap<>();
                attributedOps.put(attributionTagFilter,
                        getOrCreateDiscreteOpEventsList(attributionTagFilter));
                mAttributedOps = attributedOps;
            }

            int nTags = mAttributedOps.size();
            for (int i = nTags - 1; i >= 0; i--) {
                String tag = mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> list = mAttributedOps.valueAt(i);
                list = filterEventsList(list, beginTimeMillis, endTimeMillis, flagsFilter);
                mAttributedOps.put(tag, list);
                if (list.size() == 0) {
                    mAttributedOps.removeAt(i);
                }
            }
        }

        void addDiscreteAccess(@Nullable String attributionTag,
                @AppOpsManager.OpFlags int flags, @AppOpsManager.UidState int uidState,
                long accessTime, long accessDuration) {
            List<DiscreteOpEvent> attributedOps = getOrCreateDiscreteOpEventsList(
                    attributionTag);
            accessTime = accessTime / sDiscreteHistoryQuantization * sDiscreteHistoryQuantization;

            int nAttributedOps = attributedOps.size();
            int i = nAttributedOps;
            for (; i > 0; i--) {
                DiscreteOpEvent previousOp = attributedOps.get(i - 1);
                if (previousOp.mNoteTime < accessTime) {
                    break;
                }
                if (previousOp.mOpFlag == flags && previousOp.mUidState == uidState) {
                    if (accessDuration != previousOp.mNoteDuration
                            && accessDuration > sDiscreteHistoryQuantization) {
                        break;
                    } else {
                        return;
                    }
                }
            }
            attributedOps.add(i, new DiscreteOpEvent(accessTime, accessDuration, uidState, flags));
        }

        private List<DiscreteOpEvent> getOrCreateDiscreteOpEventsList(String attributionTag) {
            List<DiscreteOpEvent> result = mAttributedOps.get(attributionTag);
            if (result == null) {
                result = new ArrayList<>();
                mAttributedOps.put(attributionTag, result);
            }
            return result;
        }

        private void applyToHistory(AppOpsManager.HistoricalOps result, int uid,
                @NonNull String packageName, int op) {
            int nOps = mAttributedOps.size();
            for (int i = 0; i < nOps; i++) {
                String tag = mAttributedOps.keyAt(i);
                List<DiscreteOpEvent> events = mAttributedOps.valueAt(i);
                int nEvents = events.size();
                for (int j = 0; j < nEvents; j++) {
                    DiscreteOpEvent event = events.get(j);
                    result.addDiscreteAccess(op, uid, packageName, tag, event.mUidState,
                            event.mOpFlag, event.mNoteTime, event.mNoteDuration);
                }
            }
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix, int nDiscreteOps) {
            int nAttributions = mAttributedOps.size();
            for (int i = 0; i < nAttributions; i++) {
                pw.print(prefix);
                pw.print("Attribution: ");
                pw.print(mAttributedOps.keyAt(i));
                pw.println();
                List<DiscreteOpEvent> ops = mAttributedOps.valueAt(i);
                int nOps = ops.size();
                int first = nDiscreteOps < 1 ? 0 : max(0, nOps - nDiscreteOps);
                for (int j = first; j < nOps; j++) {
                    ops.get(j).dump(pw, sdf, date, prefix + "  ");

                }
            }
        }

        void serialize(TypedXmlSerializer out) throws Exception {
            int nAttributions = mAttributedOps.size();
            for (int i = 0; i < nAttributions; i++) {
                out.startTag(null, TAG_TAG);
                String tag = mAttributedOps.keyAt(i);
                if (tag != null) {
                    out.attribute(null, ATTR_TAG, mAttributedOps.keyAt(i));
                }
                List<DiscreteOpEvent> ops = mAttributedOps.valueAt(i);
                int nOps = ops.size();
                for (int j = 0; j < nOps; j++) {
                    out.startTag(null, TAG_ENTRY);
                    ops.get(j).serialize(out);
                    out.endTag(null, TAG_ENTRY);
                }
                out.endTag(null, TAG_TAG);
            }
        }

        void deserialize(TypedXmlPullParser parser, long beginTimeMillis) throws Exception {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (TAG_TAG.equals(parser.getName())) {
                    String attributionTag = parser.getAttributeValue(null, ATTR_TAG);
                    List<DiscreteOpEvent> events = getOrCreateDiscreteOpEventsList(
                            attributionTag);
                    int innerDepth = parser.getDepth();
                    while (XmlUtils.nextElementWithin(parser, innerDepth)) {
                        if (TAG_ENTRY.equals(parser.getName())) {
                            long noteTime = parser.getAttributeLong(null, ATTR_NOTE_TIME);
                            long noteDuration = parser.getAttributeLong(null, ATTR_NOTE_DURATION,
                                    -1);
                            int uidState = parser.getAttributeInt(null, ATTR_UID_STATE);
                            int opFlags = parser.getAttributeInt(null, ATTR_FLAGS);
                            if (noteTime + noteDuration < beginTimeMillis) {
                                continue;
                            }
                            DiscreteOpEvent event = new DiscreteOpEvent(noteTime, noteDuration,
                                    uidState, opFlags);
                            events.add(event);
                        }
                    }
                    Collections.sort(events, (a, b) -> a.mNoteTime < b.mNoteTime ? -1
                            : (a.mNoteTime == b.mNoteTime ? 0 : 1));
                }
            }
        }
    }

    private final class DiscreteOpEvent {
        final long mNoteTime;
        final long mNoteDuration;
        final @AppOpsManager.UidState int mUidState;
        final @AppOpsManager.OpFlags int mOpFlag;

        DiscreteOpEvent(long noteTime, long noteDuration, @AppOpsManager.UidState int uidState,
                @AppOpsManager.OpFlags int opFlag) {
            mNoteTime = noteTime;
            mNoteDuration = noteDuration;
            mUidState = uidState;
            mOpFlag = opFlag;
        }

        private void dump(@NonNull PrintWriter pw, @NonNull SimpleDateFormat sdf,
                @NonNull Date date, @NonNull String prefix) {
            pw.print(prefix);
            pw.print("Access [");
            pw.print(getUidStateName(mUidState));
            pw.print("-");
            pw.print(flagsToString(mOpFlag));
            pw.print("] at ");
            date.setTime(mNoteTime);
            pw.print(sdf.format(date));
            if (mNoteDuration != -1) {
                pw.print(" for ");
                pw.print(mNoteDuration);
                pw.print(" milliseconds ");
            }
            pw.println();
        }

        private void serialize(TypedXmlSerializer out) throws Exception {
            out.attributeLong(null, ATTR_NOTE_TIME, mNoteTime);
            if (mNoteDuration != -1) {
                out.attributeLong(null, ATTR_NOTE_DURATION, mNoteDuration);
            }
            out.attributeInt(null, ATTR_UID_STATE, mUidState);
            out.attributeInt(null, ATTR_FLAGS, mOpFlag);
        }
    }

    private static List<DiscreteOpEvent> stableListMerge(List<DiscreteOpEvent> a,
            List<DiscreteOpEvent> b) {
        int nA = a.size();
        int nB = b.size();
        int i = 0;
        int k = 0;
        List<DiscreteOpEvent> result = new ArrayList<>(nA + nB);
        while (i < nA || k < nB) {
            if (i == nA) {
                result.add(b.get(k++));
            } else if (k == nB) {
                result.add(a.get(i++));
            } else if (a.get(i).mNoteTime < b.get(k).mNoteTime) {
                result.add(a.get(i++));
            } else {
                result.add(b.get(k++));
            }
        }
        return result;
    }

    private static List<DiscreteOpEvent> filterEventsList(List<DiscreteOpEvent> list,
            long beginTimeMillis, long endTimeMillis, @AppOpsManager.OpFlags int flagsFilter) {
        int n = list.size();
        List<DiscreteOpEvent> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DiscreteOpEvent event = list.get(i);
            if ((event.mOpFlag & flagsFilter) != 0
                    && event.mNoteTime + event.mNoteDuration > beginTimeMillis
                    && event.mNoteTime < endTimeMillis) {
                result.add(event);
            }
        }
        return result;
    }
}


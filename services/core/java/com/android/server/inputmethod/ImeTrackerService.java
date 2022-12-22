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

package com.android.server.inputmethod;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.view.IImeTracker;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for managing and logging {@link ImeTracker.Token} instances.
 *
 * @implNote Suppresses {@link GuardedBy} warnings, as linter reports that {@link #mHistory}
 * interactions are guarded by {@code this} instead of {@code ImeTrackerService.this}, which should
 * be identical.
 *
 * @hide
 */
@SuppressWarnings("GuardedBy")
public final class ImeTrackerService extends IImeTracker.Stub {

    static final String TAG = "ImeTrackerService";

    /** The threshold in milliseconds after which a history entry is considered timed out. */
    private static final long TIMEOUT_MS = 10_000;

    /** Handler for registering timeouts for live entries. */
    private final Handler mHandler =
            new Handler(Looper.myLooper(), null /* callback */, true /* async */);

    /** Singleton instance of the History. */
    @GuardedBy("ImeTrackerService.this")
    private final History mHistory = new History();

    @NonNull
    @Override
    public synchronized IBinder onRequestShow(int uid, @ImeTracker.Origin int origin,
            @SoftInputShowHideReason int reason) {
        final IBinder binder = new Binder();
        final History.Entry entry = new History.Entry(uid, ImeTracker.TYPE_SHOW,
                ImeTracker.STATUS_RUN, origin, reason);
        mHistory.addEntry(binder, entry);

        // Register a delayed task to handle the case where the new entry times out.
        mHandler.postDelayed(() -> {
            synchronized (ImeTrackerService.this) {
                mHistory.setFinished(binder, ImeTracker.STATUS_TIMEOUT, ImeTracker.PHASE_NOT_SET);
            }
        }, TIMEOUT_MS);

        return binder;
    }

    @NonNull
    @Override
    public synchronized IBinder onRequestHide(int uid, @ImeTracker.Origin int origin,
            @SoftInputShowHideReason int reason) {
        final IBinder binder = new Binder();
        final History.Entry entry = new History.Entry(uid, ImeTracker.TYPE_HIDE,
                ImeTracker.STATUS_RUN, origin, reason);
        mHistory.addEntry(binder, entry);

        // Register a delayed task to handle the case where the new entry times out.
        mHandler.postDelayed(() -> {
            synchronized (ImeTrackerService.this) {
                mHistory.setFinished(binder, ImeTracker.STATUS_TIMEOUT, ImeTracker.PHASE_NOT_SET);
            }
        }, TIMEOUT_MS);

        return binder;
    }

    @Override
    public synchronized void onProgress(@NonNull IBinder statsToken, @ImeTracker.Phase int phase) {
        final History.Entry entry = mHistory.getEntry(statsToken);
        if (entry == null) return;

        entry.mPhase = phase;
    }

    @Override
    public synchronized void onFailed(@NonNull IBinder statsToken, @ImeTracker.Phase int phase) {
        mHistory.setFinished(statsToken, ImeTracker.STATUS_FAIL, phase);
    }

    @Override
    public synchronized void onCancelled(@NonNull IBinder statsToken, @ImeTracker.Phase int phase) {
        mHistory.setFinished(statsToken, ImeTracker.STATUS_CANCEL, phase);
    }

    @Override
    public synchronized void onShown(@NonNull IBinder statsToken) {
        mHistory.setFinished(statsToken, ImeTracker.STATUS_SUCCESS, ImeTracker.PHASE_NOT_SET);
    }

    @Override
    public synchronized void onHidden(@NonNull IBinder statsToken) {
        mHistory.setFinished(statsToken, ImeTracker.STATUS_SUCCESS, ImeTracker.PHASE_NOT_SET);
    }

    /**
     * Updates the IME request tracking token with new information available in IMMS.
     *
     * @param statsToken the token corresponding to the current IME request.
     * @param requestWindowName the name of the window that created the IME request.
     */
    public synchronized void onImmsUpdate(@NonNull IBinder statsToken,
            @NonNull String requestWindowName) {
        final History.Entry entry = mHistory.getEntry(statsToken);
        if (entry == null) return;

        entry.mRequestWindowName = requestWindowName;
    }

    /** Dumps the contents of the history. */
    public synchronized void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        mHistory.dump(pw, prefix);
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public synchronized boolean hasPendingImeVisibilityRequests() {
        super.hasPendingImeVisibilityRequests_enforcePermission();

        return !mHistory.mLiveEntries.isEmpty();
    }

    /**
     * A circular buffer storing the most recent few {@link ImeTracker.Token} entries information.
     */
    private static final class History {

        /** The circular buffer's capacity. */
        private static final int CAPACITY = 100;

        /** Backing store for the circular buffer. */
        @GuardedBy("ImeTrackerService.this")
        private final ArrayDeque<Entry> mEntries = new ArrayDeque<>(CAPACITY);

        /** Backing store for the live entries (i.e. entries that are not finished yet). */
        @GuardedBy("ImeTrackerService.this")
        private final WeakHashMap<IBinder, Entry> mLiveEntries = new WeakHashMap<>();

        /** Latest entry sequence number. */
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

        /** Adds a live entry. */
        @GuardedBy("ImeTrackerService.this")
        private void addEntry(@NonNull IBinder statsToken, @NonNull Entry entry) {
            mLiveEntries.put(statsToken, entry);
        }

        /** Gets the entry corresponding to the given IME tracking token, if it exists. */
        @Nullable
        @GuardedBy("ImeTrackerService.this")
        private Entry getEntry(@NonNull IBinder statsToken) {
            return mLiveEntries.get(statsToken);
        }

        /**
         * Sets the live entry corresponding to the tracking token, if it exists, as finished,
         * and uploads the data for metrics.
         *
         * @param statsToken the token corresponding to the current IME request.
         * @param status the finish status of the IME request.
         * @param phase the phase the IME request finished at, if it exists
         *              (or {@link ImeTracker#PHASE_NOT_SET} otherwise).
         */
        @GuardedBy("ImeTrackerService.this")
        private void setFinished(@NonNull IBinder statsToken, @ImeTracker.Status int status,
                @ImeTracker.Phase int phase) {
            final Entry entry = mLiveEntries.remove(statsToken);
            if (entry == null) return;

            entry.mDuration = System.currentTimeMillis() - entry.mStartTime;
            entry.mStatus = status;

            if (phase != ImeTracker.PHASE_NOT_SET) {
                entry.mPhase = phase;
            }

            // Remove excess entries overflowing capacity (plus one for the new entry).
            while (mEntries.size() >= CAPACITY) {
                mEntries.remove();
            }

            mEntries.offer(entry);

            // Log newly finished entry.
            FrameworkStatsLog.write(FrameworkStatsLog.IME_REQUEST_FINISHED, entry.mUid,
                    entry.mDuration, entry.mType, entry.mStatus, entry.mReason,
                    entry.mOrigin, entry.mPhase);
        }

        /** Dumps the contents of the circular buffer. */
        @GuardedBy("ImeTrackerService.this")
        private void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            final DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                            .withZone(ZoneId.systemDefault());

            pw.print(prefix);
            pw.println("ImeTrackerService#History.mLiveEntries:");

            for (final Entry entry: mLiveEntries.values()) {
                dumpEntry(entry, pw, prefix, formatter);
            }

            pw.print(prefix);
            pw.println("ImeTrackerService#History.mEntries:");

            for (final Entry entry: mEntries) {
                dumpEntry(entry, pw, prefix, formatter);
            }
        }

        @GuardedBy("ImeTrackerService.this")
        private void dumpEntry(@NonNull Entry entry, @NonNull PrintWriter pw,
                @NonNull String prefix, @NonNull DateTimeFormatter formatter) {
            pw.print(prefix);
            pw.println("ImeTrackerService#History #" + entry.mSequenceNumber + ":");

            pw.print(prefix);
            pw.println(" startTime=" + formatter.format(Instant.ofEpochMilli(entry.mStartTime)));

            pw.print(prefix);
            pw.println(" duration=" + entry.mDuration + "ms");

            pw.print(prefix);
            pw.print(" type=" + ImeTracker.Debug.typeToString(entry.mType));

            pw.print(prefix);
            pw.print(" status=" + ImeTracker.Debug.statusToString(entry.mStatus));

            pw.print(prefix);
            pw.print(" origin="
                    + ImeTracker.Debug.originToString(entry.mOrigin));

            pw.print(prefix);
            pw.print(" reason="
                    + InputMethodDebug.softInputDisplayReasonToString(entry.mReason));

            pw.print(prefix);
            pw.print(" phase="
                    + ImeTracker.Debug.phaseToString(entry.mPhase));

            pw.print(prefix);
            pw.print(" requestWindowName=" + entry.mRequestWindowName);
        }

        /** A history entry. */
        private static final class Entry {

            /** The entry's sequence number in the history. */
            private final int mSequenceNumber = sSequenceNumber.getAndIncrement();

            /** Uid of the client that requested the IME. */
            private final int mUid;

            /** Clock time in milliseconds when the IME request was created. */
            private final long mStartTime = System.currentTimeMillis();

            /** Duration in milliseconds of the IME request from start to end. */
            private long mDuration = 0;

            /** Type of the IME request. */
            @ImeTracker.Type
            private final int mType;

            /** Status of the IME request. */
            @ImeTracker.Status
            private int mStatus;

            /** Origin of the IME request. */
            @ImeTracker.Origin
            private final int mOrigin;

            /** Reason for creating the IME request. */
            @SoftInputShowHideReason
            private final int mReason;

            /** Latest phase of the IME request. */
            @ImeTracker.Phase
            private int mPhase = ImeTracker.PHASE_NOT_SET;

            /**
             * Name of the window that created the IME request.
             *
             * Note: This is later set through {@link #onImmsUpdate(IBinder, String)}.
             */
            @NonNull
            private String mRequestWindowName = "not set";

            private Entry(int uid, @ImeTracker.Type int type, @ImeTracker.Status int status,
                    @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason) {
                mUid = uid;
                mType = type;
                mStatus = status;
                mOrigin = origin;
                mReason = reason;
            }
        }
    }
}

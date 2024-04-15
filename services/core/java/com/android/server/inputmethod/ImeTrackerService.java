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
import android.util.Log;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.util.FrameworkStatsLog;

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

    private static final String TAG = ImeTracker.TAG;

    /** The threshold in milliseconds after which a history entry is considered timed out. */
    private static final long TIMEOUT_MS = 10_000;

    /** Handler for registering timeouts for live entries. */
    @GuardedBy("mLock")
    private final Handler mHandler;

    /** Singleton instance of the History. */
    @GuardedBy("mLock")
    private final History mHistory = new History();

    private final Object mLock = new Object();

    ImeTrackerService(@NonNull Looper looper) {
        mHandler = new Handler(looper, null /* callback */, true /* async */);
    }

    @NonNull
    @Override
    public ImeTracker.Token onStart(@NonNull String tag, int uid, @ImeTracker.Type int type,
            @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason, boolean fromUser) {
        final var binder = new Binder();
        final var token = new ImeTracker.Token(binder, tag);
        final var entry = new History.Entry(tag, uid, type, ImeTracker.STATUS_RUN, origin, reason,
                fromUser);
        synchronized (mLock) {
            mHistory.addEntry(binder, entry);

            // Register a delayed task to handle the case where the new entry times out.
            mHandler.postDelayed(() -> {
                synchronized (mLock) {
                    mHistory.setFinished(token, ImeTracker.STATUS_TIMEOUT,
                            ImeTracker.PHASE_NOT_SET);
                }
            }, TIMEOUT_MS);
        }
        return token;
    }

    @Override
    public void onProgress(@NonNull IBinder binder, @ImeTracker.Phase int phase) {
        synchronized (mLock) {
            final var entry = mHistory.getEntry(binder);
            if (entry == null) return;

            entry.mPhase = phase;
        }
    }

    @Override
    public void onFailed(@NonNull ImeTracker.Token statsToken, @ImeTracker.Phase int phase) {
        synchronized (mLock) {
            mHistory.setFinished(statsToken, ImeTracker.STATUS_FAIL, phase);
        }
    }

    @Override
    public void onCancelled(@NonNull ImeTracker.Token statsToken, @ImeTracker.Phase int phase) {
        synchronized (mLock) {
            mHistory.setFinished(statsToken, ImeTracker.STATUS_CANCEL, phase);
        }
    }

    @Override
    public void onShown(@NonNull ImeTracker.Token statsToken) {
        synchronized (mLock) {
            mHistory.setFinished(statsToken, ImeTracker.STATUS_SUCCESS, ImeTracker.PHASE_NOT_SET);
        }
    }

    @Override
    public void onHidden(@NonNull ImeTracker.Token statsToken) {
        synchronized (mLock) {
            mHistory.setFinished(statsToken, ImeTracker.STATUS_SUCCESS, ImeTracker.PHASE_NOT_SET);
        }
    }

    @Override
    public void onDispatched(@NonNull ImeTracker.Token statsToken) {
        synchronized (mLock) {
            mHistory.setFinished(statsToken, ImeTracker.STATUS_SUCCESS, ImeTracker.PHASE_NOT_SET);
        }
    }

    /**
     * Updates the IME request tracking token with new information available in IMMS.
     *
     * @param statsToken the token tracking the current IME request.
     * @param requestWindowName the name of the window that created the IME request.
     */
    public void onImmsUpdate(@NonNull ImeTracker.Token statsToken,
            @NonNull String requestWindowName) {
        synchronized (mLock) {
            final var entry = mHistory.getEntry(statsToken.getBinder());
            if (entry == null) return;

            entry.mRequestWindowName = requestWindowName;
        }
    }

    /** Dumps the contents of the history. */
    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        synchronized (mLock) {
            mHistory.dump(pw, prefix);
        }
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public boolean hasPendingImeVisibilityRequests() {
        super.hasPendingImeVisibilityRequests_enforcePermission();
        synchronized (mLock) {
            return !mHistory.mLiveEntries.isEmpty();
        }
    }

    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void finishTrackingPendingImeVisibilityRequests() {
        super.finishTrackingPendingImeVisibilityRequests_enforcePermission();
        synchronized (mLock) {
            mHistory.mLiveEntries.clear();
        }
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

        /** Adds a live entry corresponding to the given IME tracking token's binder. */
        @GuardedBy("ImeTrackerService.this")
        private void addEntry(@NonNull IBinder binder, @NonNull Entry entry) {
            mLiveEntries.put(binder, entry);
        }

        /** Gets the entry corresponding to the given IME tracking token's binder, if it exists. */
        @Nullable
        @GuardedBy("ImeTrackerService.this")
        private Entry getEntry(@NonNull IBinder binder) {
            return mLiveEntries.get(binder);
        }

        /**
         * Sets the live entry corresponding to the tracking token, if it exists, as finished,
         * and uploads the data for metrics.
         *
         * @param statsToken the token tracking the current IME request.
         * @param status the finish status of the IME request.
         * @param phase the phase the IME request finished at, if it exists
         *              (or {@link ImeTracker#PHASE_NOT_SET} otherwise).
         */
        @GuardedBy("ImeTrackerService.this")
        private void setFinished(@NonNull ImeTracker.Token statsToken,
                @ImeTracker.Status int status, @ImeTracker.Phase int phase) {
            final var entry = mLiveEntries.remove(statsToken.getBinder());
            if (entry == null) {
                // This will be unconditionally called through the postDelayed above to handle
                // potential timeouts, and is thus intentionally dropped to avoid having to manually
                // save and remove the registered callback. Only timeout calls are expected.
                if (status != ImeTracker.STATUS_TIMEOUT) {
                    Log.i(TAG, statsToken.getTag()
                            + ": setFinished on previously finished token at "
                            + ImeTracker.Debug.phaseToString(phase) + " with "
                            + ImeTracker.Debug.statusToString(status));
                }
                return;
            }

            entry.mDuration = System.currentTimeMillis() - entry.mStartTime;
            entry.mStatus = status;

            if (phase != ImeTracker.PHASE_NOT_SET) {
                entry.mPhase = phase;
            }

            if (status == ImeTracker.STATUS_TIMEOUT) {
                // All events other than timeouts are already logged in the client-side ImeTracker.
                Log.i(TAG, statsToken.getTag() + ": setFinished at "
                        + ImeTracker.Debug.phaseToString(entry.mPhase) + " with "
                        + ImeTracker.Debug.statusToString(status));
            }

            // Remove excess entries overflowing capacity (plus one for the new entry).
            while (mEntries.size() >= CAPACITY) {
                mEntries.remove();
            }

            mEntries.offer(entry);

            // Log newly finished entry.
            FrameworkStatsLog.write(FrameworkStatsLog.IME_REQUEST_FINISHED, entry.mUid,
                    entry.mDuration, entry.mType, entry.mStatus, entry.mReason,
                    entry.mOrigin, entry.mPhase, entry.mFromUser);
        }

        /** Dumps the contents of the circular buffer. */
        @GuardedBy("ImeTrackerService.this")
        private void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            final var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .withZone(ZoneId.systemDefault());

            pw.print(prefix);
            pw.println("mLiveEntries: " + mLiveEntries.size() + " elements");

            for (final var entry: mLiveEntries.values()) {
                dumpEntry(entry, pw, prefix + "  ", formatter);
            }
            pw.print(prefix);
            pw.println("mEntries: " + mEntries.size() + " elements");

            for (final var entry: mEntries) {
                dumpEntry(entry, pw, prefix + "  ", formatter);
            }
        }

        @GuardedBy("ImeTrackerService.this")
        private void dumpEntry(@NonNull Entry entry, @NonNull PrintWriter pw,
                @NonNull String prefix, @NonNull DateTimeFormatter formatter) {
            pw.print(prefix);
            pw.print("#" + entry.mSequenceNumber);
            pw.print(" " + ImeTracker.Debug.typeToString(entry.mType));
            pw.print(" - " + ImeTracker.Debug.statusToString(entry.mStatus));
            pw.print(" - " + entry.mTag);
            pw.println(" (" + entry.mDuration + "ms):");

            pw.print(prefix);
            pw.print("  startTime=" + formatter.format(Instant.ofEpochMilli(entry.mStartTime)));
            pw.println(" " + ImeTracker.Debug.originToString(entry.mOrigin));

            pw.print(prefix);
            pw.print("  reason=" + InputMethodDebug.softInputDisplayReasonToString(entry.mReason));
            pw.println(" " + ImeTracker.Debug.phaseToString(entry.mPhase));

            pw.print(prefix);
            pw.println("  requestWindowName=" + entry.mRequestWindowName);
        }

        /** A history entry. */
        private static final class Entry {

            /** The entry's sequence number in the history. */
            private final int mSequenceNumber = sSequenceNumber.getAndIncrement();

            /** Logging tag, of the shape "component:random_hexadecimal". */
            @NonNull
            private final String mTag;

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

            /** Whether this request was created directly from a user interaction. */
            private final boolean mFromUser;

            /**
             * Name of the window that created the IME request.
             *
             * Note: This is later set through {@link #onImmsUpdate}.
             */
            @NonNull
            private String mRequestWindowName = "not set";

            private Entry(@NonNull String tag, int uid, @ImeTracker.Type int type,
                    @ImeTracker.Status int status, @ImeTracker.Origin int origin,
                    @SoftInputShowHideReason int reason, boolean fromUser) {
                mTag = tag;
                mUid = uid;
                mType = type;
                mStatus = status;
                mOrigin = origin;
                mReason = reason;
                mFromUser = fromUser;
            }
        }
    }
}

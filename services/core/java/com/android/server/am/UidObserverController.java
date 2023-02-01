/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.am;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerProto;
import android.app.IUidObserver;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.ActivityManagerServiceDumpProcessesProto.UidObserverRegistrationProto;

import java.io.PrintWriter;
import java.util.ArrayList;

public class UidObserverController {
    /** If a UID observer takes more than this long, send a WTF. */
    private static final int SLOW_UID_OBSERVER_THRESHOLD_MS = 20;

    private final Handler mHandler;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    final RemoteCallbackList<IUidObserver> mUidObservers = new RemoteCallbackList<>();

    @GuardedBy("mLock")
    private final ArrayList<ChangeRecord> mPendingUidChanges = new ArrayList<>();
    @GuardedBy("mLock")
    private final ArrayList<ChangeRecord> mAvailUidChanges = new ArrayList<>();

    private ChangeRecord[] mActiveUidChanges = new ChangeRecord[5];

    /** Total # of UID change events dispatched, shown in dumpsys. */
    @GuardedBy("mLock")
    private int mUidChangeDispatchCount;

    private final Runnable mDispatchRunnable = this::dispatchUidsChanged;

    /**
     * This is for verifying the UID report flow.
     */
    private static final boolean VALIDATE_UID_STATES = true;
    private final ActiveUids mValidateUids;

    UidObserverController(@NonNull Handler handler) {
        mHandler = handler;
        mValidateUids = new ActiveUids(null /* service */, false /* postChangesToAtm */);
    }

    void register(@NonNull IUidObserver observer, int which, int cutpoint,
            @NonNull String callingPackage, int callingUid) {
        synchronized (mLock) {
            mUidObservers.register(observer, new UidObserverRegistration(callingUid,
                    callingPackage, which, cutpoint,
                    ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL, callingUid)
                    == PackageManager.PERMISSION_GRANTED));
        }
    }

    void unregister(@NonNull IUidObserver observer) {
        synchronized (mLock) {
            mUidObservers.unregister(observer);
        }
    }

    int enqueueUidChange(@Nullable ChangeRecord currentRecord, int uid, int change, int procState,
            long procStateSeq, int capability, boolean ephemeral) {
        synchronized (mLock) {
            if (mPendingUidChanges.size() == 0) {
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "*** Enqueueing dispatch uid changed!");
                }
                mHandler.post(mDispatchRunnable);
            }

            final ChangeRecord changeRecord = currentRecord != null
                    ? currentRecord : getOrCreateChangeRecordLocked();
            if (!changeRecord.isPending) {
                changeRecord.isPending = true;
                mPendingUidChanges.add(changeRecord);
            } else {
                change = mergeWithPendingChange(change, changeRecord.change);
            }

            changeRecord.uid = uid;
            changeRecord.change = change;
            changeRecord.procState = procState;
            changeRecord.procStateSeq = procStateSeq;
            changeRecord.capability = capability;
            changeRecord.ephemeral = ephemeral;

            return changeRecord.change;
        }
    }

    ArrayList<ChangeRecord> getPendingUidChangesForTest() {
        return mPendingUidChanges;
    }

    ActiveUids getValidateUidsForTest() {
        return mValidateUids;
    }

    Runnable getDispatchRunnableForTest() {
        return mDispatchRunnable;
    }

    @VisibleForTesting
    static int mergeWithPendingChange(int currentChange, int pendingChange) {
        // If there is no change in idle or active state, then keep whatever was pending.
        if ((currentChange & (UidRecord.CHANGE_IDLE | UidRecord.CHANGE_ACTIVE)) == 0) {
            currentChange |= (pendingChange & (UidRecord.CHANGE_IDLE
                    | UidRecord.CHANGE_ACTIVE));
        }
        // If there is no change in cached or uncached state, then keep whatever was pending.
        if ((currentChange & (UidRecord.CHANGE_CACHED | UidRecord.CHANGE_UNCACHED)) == 0) {
            currentChange |= (pendingChange & (UidRecord.CHANGE_CACHED
                    | UidRecord.CHANGE_UNCACHED));
        }
        // If this is a report of the UID being gone, then we shouldn't keep any previous
        // report of it being active or cached.  (That is, a gone uid is never active,
        // and never cached.)
        if ((currentChange & UidRecord.CHANGE_GONE) != 0) {
            currentChange &= ~(UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_CACHED);
        }
        if ((pendingChange & UidRecord.CHANGE_CAPABILITY) != 0) {
            currentChange |= UidRecord.CHANGE_CAPABILITY;
        }
        if ((pendingChange & UidRecord.CHANGE_PROCSTATE) != 0) {
            currentChange |= UidRecord.CHANGE_PROCSTATE;
        }
        if ((pendingChange & UidRecord.CHANGE_PROCADJ) != 0) {
            currentChange |= UidRecord.CHANGE_PROCADJ;
        }
        return currentChange;
    }

    @GuardedBy("mLock")
    private ChangeRecord getOrCreateChangeRecordLocked() {
        final ChangeRecord changeRecord;
        final int size = mAvailUidChanges.size();
        if (size > 0) {
            changeRecord = mAvailUidChanges.remove(size - 1);
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "Retrieving available item: " + changeRecord);
            }
        } else {
            changeRecord = new ChangeRecord();
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "Allocating new item: " + changeRecord);
            }
        }
        return changeRecord;
    }

    @VisibleForTesting
    void dispatchUidsChanged() {
        final int numUidChanges;
        synchronized (mLock) {
            numUidChanges = mPendingUidChanges.size();
            if (mActiveUidChanges.length < numUidChanges) {
                mActiveUidChanges = new ChangeRecord[numUidChanges];
            }
            for (int i = 0; i < numUidChanges; i++) {
                final ChangeRecord changeRecord = mPendingUidChanges.get(i);
                mActiveUidChanges[i] = getOrCreateChangeRecordLocked();
                changeRecord.copyTo(mActiveUidChanges[i]);
                changeRecord.isPending = false;
            }
            mPendingUidChanges.clear();
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "*** Delivering " + numUidChanges + " uid changes");
            }
            mUidChangeDispatchCount += numUidChanges;
        }

        int i = mUidObservers.beginBroadcast();
        while (i-- > 0) {
            dispatchUidsChangedForObserver(mUidObservers.getBroadcastItem(i),
                    (UidObserverRegistration) mUidObservers.getBroadcastCookie(i), numUidChanges);
        }
        mUidObservers.finishBroadcast();

        if (VALIDATE_UID_STATES && mUidObservers.getRegisteredCallbackCount() > 0) {
            for (int j = 0; j < numUidChanges; ++j) {
                final ChangeRecord item = mActiveUidChanges[j];
                if ((item.change & UidRecord.CHANGE_GONE) != 0) {
                    mValidateUids.remove(item.uid);
                } else {
                    UidRecord validateUid = mValidateUids.get(item.uid);
                    if (validateUid == null) {
                        validateUid = new UidRecord(item.uid, null);
                        mValidateUids.put(item.uid, validateUid);
                    }
                    if ((item.change & UidRecord.CHANGE_IDLE) != 0) {
                        validateUid.setIdle(true);
                    } else if ((item.change & UidRecord.CHANGE_ACTIVE) != 0) {
                        validateUid.setIdle(false);
                    }
                    validateUid.setSetProcState(item.procState);
                    validateUid.setCurProcState(item.procState);
                    validateUid.setSetCapability(item.capability);
                    validateUid.setCurCapability(item.capability);
                }
            }
        }

        synchronized (mLock) {
            for (int j = 0; j < numUidChanges; j++) {
                final ChangeRecord changeRecord = mActiveUidChanges[j];
                changeRecord.isPending = false;
                mAvailUidChanges.add(changeRecord);
            }
        }
    }

    private void dispatchUidsChangedForObserver(@NonNull IUidObserver observer,
            @NonNull UidObserverRegistration reg, int changesSize) {
        if (observer == null) {
            return;
        }
        try {
            for (int j = 0; j < changesSize; j++) {
                final ChangeRecord item = mActiveUidChanges[j];
                final long start = SystemClock.uptimeMillis();
                final int change = item.change;
                // Does the user have permission? Don't send a non user UID change otherwise
                if (UserHandle.getUserId(item.uid) != UserHandle.getUserId(reg.mUid)
                        && !reg.mCanInteractAcrossUsers) {
                    continue;
                }
                if (change == UidRecord.CHANGE_PROCSTATE
                        && (reg.mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) == 0) {
                    // No-op common case: no significant change, the observer is not
                    // interested in all proc state changes.
                    continue;
                }
                if (change == UidRecord.CHANGE_PROCADJ
                        && (reg.mWhich & ActivityManager.UID_OBSERVER_PROC_OOM_ADJ) == 0) {
                    // No-op common case: no significant change, the observer is not
                    // interested in proc adj changes.
                    continue;
                }
                if ((change & UidRecord.CHANGE_IDLE) != 0) {
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID idle uid=" + item.uid);
                        }
                        observer.onUidIdle(item.uid, item.ephemeral);
                    }
                } else if ((change & UidRecord.CHANGE_ACTIVE) != 0) {
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID active uid=" + item.uid);
                        }
                        observer.onUidActive(item.uid);
                    }
                }
                if ((reg.mWhich & ActivityManager.UID_OBSERVER_CACHED) != 0) {
                    if ((change & UidRecord.CHANGE_CACHED) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID cached uid=" + item.uid);
                        }
                        observer.onUidCachedChanged(item.uid, true);
                    } else if ((change & UidRecord.CHANGE_UNCACHED) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID active uid=" + item.uid);
                        }
                        observer.onUidCachedChanged(item.uid, false);
                    }
                }
                if ((change & UidRecord.CHANGE_GONE) != 0) {
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_GONE) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID gone uid=" + item.uid);
                        }
                        observer.onUidGone(item.uid, item.ephemeral);
                    }
                    if (reg.mLastProcStates != null) {
                        reg.mLastProcStates.delete(item.uid);
                    }
                } else {
                    boolean doReport = false;
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                        doReport = true;
                        if (reg.mCutpoint >= ActivityManager.MIN_PROCESS_STATE) {
                            final int lastState = reg.mLastProcStates.get(item.uid,
                                    ActivityManager.PROCESS_STATE_UNKNOWN);
                            if (lastState != ActivityManager.PROCESS_STATE_UNKNOWN) {
                                final boolean lastAboveCut = lastState <= reg.mCutpoint;
                                final boolean newAboveCut = item.procState <= reg.mCutpoint;
                                doReport = lastAboveCut != newAboveCut;
                            } else {
                                doReport = item.procState != PROCESS_STATE_NONEXISTENT;
                            }
                        }
                    }
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_CAPABILITY) != 0) {
                        doReport |= (change & UidRecord.CHANGE_CAPABILITY) != 0;
                    }
                    if (doReport) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID CHANGED uid=" + item.uid
                                    + ": " + item.procState + ": " + item.capability);
                        }
                        if (reg.mLastProcStates != null) {
                            reg.mLastProcStates.put(item.uid, item.procState);
                        }
                        observer.onUidStateChanged(item.uid, item.procState,
                                item.procStateSeq,
                                item.capability);
                    }
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_PROC_OOM_ADJ) != 0
                            && (change & UidRecord.CHANGE_PROCADJ) != 0) {
                        observer.onUidProcAdjChanged(item.uid);
                    }
                }
                final int duration = (int) (SystemClock.uptimeMillis() - start);
                if (reg.mMaxDispatchTime < duration) {
                    reg.mMaxDispatchTime = duration;
                }
                if (duration >= SLOW_UID_OBSERVER_THRESHOLD_MS) {
                    reg.mSlowDispatchCount++;
                }
            }
        } catch (RemoteException e) {
        }
    }

    UidRecord getValidateUidRecord(int uid) {
        return mValidateUids.get(uid);
    }

    void dump(@NonNull PrintWriter pw, @Nullable String dumpPackage) {
        synchronized (mLock) {
            final int count = mUidObservers.getRegisteredCallbackCount();
            boolean printed = false;
            for (int i = 0; i < count; i++) {
                final UidObserverRegistration reg = (UidObserverRegistration)
                        mUidObservers.getRegisteredCallbackCookie(i);
                if (dumpPackage == null || dumpPackage.equals(reg.mPkg)) {
                    if (!printed) {
                        pw.println("  mUidObservers:");
                        printed = true;
                    }
                    reg.dump(pw, mUidObservers.getRegisteredCallbackItem(i));
                }
            }

            pw.println();
            pw.print("  mUidChangeDispatchCount=");
            pw.print(mUidChangeDispatchCount);
            pw.println();
            pw.println("  Slow UID dispatches:");
            for (int i = 0; i < count; i++) {
                final UidObserverRegistration reg = (UidObserverRegistration)
                        mUidObservers.getRegisteredCallbackCookie(i);
                pw.print("    ");
                pw.print(mUidObservers.getRegisteredCallbackItem(i).getClass().getTypeName());
                pw.print(": ");
                pw.print(reg.mSlowDispatchCount);
                pw.print(" / Max ");
                pw.print(reg.mMaxDispatchTime);
                pw.println("ms");
            }
        }
    }

    void dumpDebug(@NonNull ProtoOutputStream proto, @Nullable String dumpPackage) {
        synchronized (mLock) {
            final int count = mUidObservers.getRegisteredCallbackCount();
            for (int i = 0; i < count; i++) {
                final UidObserverRegistration reg = (UidObserverRegistration)
                        mUidObservers.getRegisteredCallbackCookie(i);
                if (dumpPackage == null || dumpPackage.equals(reg.mPkg)) {
                    reg.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.UID_OBSERVERS);
                }
            }
        }
    }

    boolean dumpValidateUids(@NonNull PrintWriter pw, @Nullable String dumpPackage, int dumpAppId,
            @NonNull String header, boolean needSep) {
        return mValidateUids.dump(pw, dumpPackage, dumpAppId, header, needSep);
    }

    void dumpValidateUidsProto(@NonNull ProtoOutputStream proto, @Nullable String dumpPackage,
            int dumpAppId, long fieldId) {
        mValidateUids.dumpProto(proto, dumpPackage, dumpAppId, fieldId);
    }

    static final class ChangeRecord {
        public boolean isPending;
        public int uid;
        public int change;
        public int procState;
        public int capability;
        public boolean ephemeral;
        public long procStateSeq;

        void copyTo(@NonNull ChangeRecord changeRecord) {
            changeRecord.isPending = isPending;
            changeRecord.uid = uid;
            changeRecord.change = change;
            changeRecord.procState = procState;
            changeRecord.capability = capability;
            changeRecord.ephemeral = ephemeral;
            changeRecord.procStateSeq = procStateSeq;
        }
    }

    private static final class UidObserverRegistration {
        private final int mUid;
        private final String mPkg;
        private final int mWhich;
        private final int mCutpoint;
        private final boolean mCanInteractAcrossUsers;

        /**
         * Total # of callback calls that took more than {@link #SLOW_UID_OBSERVER_THRESHOLD_MS}.
         * We show it in dumpsys.
         */
        int mSlowDispatchCount;

        /** Max time it took for each dispatch. */
        int mMaxDispatchTime;

        final SparseIntArray mLastProcStates;

        // Please keep the enum lists in sync
        private static final int[] ORIG_ENUMS = new int[]{
                ActivityManager.UID_OBSERVER_IDLE,
                ActivityManager.UID_OBSERVER_ACTIVE,
                ActivityManager.UID_OBSERVER_GONE,
                ActivityManager.UID_OBSERVER_PROCSTATE,
                ActivityManager.UID_OBSERVER_CAPABILITY,
                ActivityManager.UID_OBSERVER_PROC_OOM_ADJ,
        };
        private static final int[] PROTO_ENUMS = new int[]{
                ActivityManagerProto.UID_OBSERVER_FLAG_IDLE,
                ActivityManagerProto.UID_OBSERVER_FLAG_ACTIVE,
                ActivityManagerProto.UID_OBSERVER_FLAG_GONE,
                ActivityManagerProto.UID_OBSERVER_FLAG_PROCSTATE,
                ActivityManagerProto.UID_OBSERVER_FLAG_CAPABILITY,
                ActivityManagerProto.UID_OBSERVER_FLAG_PROC_OOM_ADJ,
        };

        UidObserverRegistration(int uid, @NonNull String pkg, int which, int cutpoint,
                boolean canInteractAcrossUsers) {
            this.mUid = uid;
            this.mPkg = pkg;
            this.mWhich = which;
            this.mCutpoint = cutpoint;
            this.mCanInteractAcrossUsers = canInteractAcrossUsers;
            mLastProcStates = cutpoint >= ActivityManager.MIN_PROCESS_STATE
                    ? new SparseIntArray() : null;
        }

        void dump(@NonNull PrintWriter pw, @NonNull IUidObserver observer) {
            pw.print("    ");
            UserHandle.formatUid(pw, mUid);
            pw.print(" ");
            pw.print(mPkg);
            pw.print(" ");
            pw.print(observer.getClass().getTypeName());
            pw.print(":");
            if ((mWhich & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                pw.print(" IDLE");
            }
            if ((mWhich & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                pw.print(" ACT");
            }
            if ((mWhich & ActivityManager.UID_OBSERVER_GONE) != 0) {
                pw.print(" GONE");
            }
            if ((mWhich & ActivityManager.UID_OBSERVER_CAPABILITY) != 0) {
                pw.print(" CAP");
            }
            if ((mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                pw.print(" STATE");
                pw.print(" (cut=");
                pw.print(mCutpoint);
                pw.print(")");
            }
            pw.println();
            if (mLastProcStates != null) {
                final int size = mLastProcStates.size();
                for (int j = 0; j < size; j++) {
                    pw.print("      Last ");
                    UserHandle.formatUid(pw, mLastProcStates.keyAt(j));
                    pw.print(": ");
                    pw.println(mLastProcStates.valueAt(j));
                }
            }
        }

        void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(UidObserverRegistrationProto.UID, mUid);
            proto.write(UidObserverRegistrationProto.PACKAGE, mPkg);
            ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, UidObserverRegistrationProto.FLAGS,
                    mWhich, ORIG_ENUMS, PROTO_ENUMS);
            proto.write(UidObserverRegistrationProto.CUT_POINT, mCutpoint);
            if (mLastProcStates != null) {
                final int size = mLastProcStates.size();
                for (int i = 0; i < size; i++) {
                    final long pToken = proto.start(UidObserverRegistrationProto.LAST_PROC_STATES);
                    proto.write(UidObserverRegistrationProto.ProcState.UID,
                            mLastProcStates.keyAt(i));
                    proto.write(UidObserverRegistrationProto.ProcState.STATE,
                            mLastProcStates.valueAt(i));
                    proto.end(pToken);
                }
            }
            proto.end(token);
        }
    }
}

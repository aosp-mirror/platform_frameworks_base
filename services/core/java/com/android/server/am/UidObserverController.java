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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;

import android.app.ActivityManager;
import android.app.ActivityManagerProto;
import android.app.IUidObserver;
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
    private final ActivityManagerService mService;
    final RemoteCallbackList<IUidObserver> mUidObservers = new RemoteCallbackList<>();

    UidRecord.ChangeItem[] mActiveUidChanges = new UidRecord.ChangeItem[5];
    final ArrayList<UidRecord.ChangeItem> mPendingUidChanges = new ArrayList<>();
    final ArrayList<UidRecord.ChangeItem> mAvailUidChanges = new ArrayList<>();

    /** Total # of UID change events dispatched, shown in dumpsys. */
    int mUidChangeDispatchCount;

    /** If a UID observer takes more than this long, send a WTF. */
    private static final int SLOW_UID_OBSERVER_THRESHOLD_MS = 20;

    /**
     * This is for verifying the UID report flow.
     */
    static final boolean VALIDATE_UID_STATES = true;
    final ActiveUids mValidateUids;

    UidObserverController(ActivityManagerService service) {
        mService = service;
        mValidateUids = new ActiveUids(mService, false /* postChangesToAtm */);
    }

    @GuardedBy("mService")
    void register(IUidObserver observer, int which, int cutpoint, String callingPackage,
            int callingUid) {
        mUidObservers.register(observer, new UidObserverRegistration(callingUid,
                callingPackage, which, cutpoint));
    }

    @GuardedBy("mService")
    void unregister(IUidObserver observer) {
        mUidObservers.unregister(observer);
    }

    @GuardedBy("mService")
    final void enqueueUidChangeLocked(UidRecord uidRec, int uid, int change) {
        final UidRecord.ChangeItem pendingChange;
        if (uidRec == null || uidRec.pendingChange == null) {
            if (mPendingUidChanges.size() == 0) {
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "*** Enqueueing dispatch uid changed!");
                }
                mService.mUiHandler.post(this::dispatchUidsChanged);
            }
            final int size = mAvailUidChanges.size();
            if (size > 0) {
                pendingChange = mAvailUidChanges.remove(size - 1);
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "Retrieving available item: " + pendingChange);
                }
            } else {
                pendingChange = new UidRecord.ChangeItem();
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "Allocating new item: " + pendingChange);
                }
            }
            if (uidRec != null) {
                uidRec.pendingChange = pendingChange;
                if ((change & UidRecord.CHANGE_GONE) != 0 && !uidRec.idle) {
                    // If this uid is going away, and we haven't yet reported it is gone,
                    // then do so now.
                    change |= UidRecord.CHANGE_IDLE;
                }
            } else if (uid < 0) {
                throw new IllegalArgumentException("No UidRecord or uid");
            }
            pendingChange.uidRecord = uidRec;
            pendingChange.uid = uidRec != null ? uidRec.uid : uid;
            mPendingUidChanges.add(pendingChange);
        } else {
            pendingChange = uidRec.pendingChange;
            // If there is no change in idle or active state, then keep whatever was pending.
            if ((change & (UidRecord.CHANGE_IDLE | UidRecord.CHANGE_ACTIVE)) == 0) {
                change |= (pendingChange.change & (UidRecord.CHANGE_IDLE
                        | UidRecord.CHANGE_ACTIVE));
            }
            // If there is no change in cached or uncached state, then keep whatever was pending.
            if ((change & (UidRecord.CHANGE_CACHED | UidRecord.CHANGE_UNCACHED)) == 0) {
                change |= (pendingChange.change & (UidRecord.CHANGE_CACHED
                        | UidRecord.CHANGE_UNCACHED));
            }
            // If this is a report of the UID being gone, then we shouldn't keep any previous
            // report of it being active or cached.  (That is, a gone uid is never active,
            // and never cached.)
            if ((change & UidRecord.CHANGE_GONE) != 0) {
                change &= ~(UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_CACHED);
                if (!uidRec.idle) {
                    // If this uid is going away, and we haven't yet reported it is gone,
                    // then do so now.
                    change |= UidRecord.CHANGE_IDLE;
                }
            }
        }
        pendingChange.change = change;
        pendingChange.processState = uidRec != null
                ? uidRec.setProcState : PROCESS_STATE_NONEXISTENT;
        pendingChange.capability = uidRec != null ? uidRec.setCapability : 0;
        pendingChange.ephemeral = uidRec != null ? uidRec.ephemeral : isEphemeralLocked(uid);
        pendingChange.procStateSeq = uidRec != null ? uidRec.curProcStateSeq : 0;
        if (uidRec != null) {
            uidRec.lastReportedChange = change;
            uidRec.updateLastDispatchedProcStateSeq(change);
        }

        // Directly update the power manager, since we sit on top of it and it is critical
        // it be kept in sync (so wake locks will be held as soon as appropriate).
        if (mService.mLocalPowerManager != null) {
            // TO DO: dispatch cached/uncached changes here, so we don't need to report
            // all proc state changes.
            if ((change & UidRecord.CHANGE_ACTIVE) != 0) {
                mService.mLocalPowerManager.uidActive(pendingChange.uid);
            }
            if ((change & UidRecord.CHANGE_IDLE) != 0) {
                mService.mLocalPowerManager.uidIdle(pendingChange.uid);
            }
            if ((change & UidRecord.CHANGE_GONE) != 0) {
                mService.mLocalPowerManager.uidGone(pendingChange.uid);
            } else {
                mService.mLocalPowerManager.updateUidProcState(pendingChange.uid,
                        pendingChange.processState);
            }
        }
    }

    @VisibleForTesting
    void dispatchUidsChanged() {
        int numUidChanges;
        synchronized (mService) {
            numUidChanges = mPendingUidChanges.size();
            if (mActiveUidChanges.length < numUidChanges) {
                mActiveUidChanges = new UidRecord.ChangeItem[numUidChanges];
            }
            for (int i = 0; i < numUidChanges; i++) {
                final UidRecord.ChangeItem change = mPendingUidChanges.get(i);
                mActiveUidChanges[i] = change;
                if (change.uidRecord != null) {
                    change.uidRecord.pendingChange = null;
                    change.uidRecord = null;
                }
            }
            mPendingUidChanges.clear();
            if (DEBUG_UID_OBSERVERS) {
                Slog.i(TAG_UID_OBSERVERS, "*** Delivering " + numUidChanges + " uid changes");
            }
        }

        mUidChangeDispatchCount += numUidChanges;
        int i = mUidObservers.beginBroadcast();
        while (i > 0) {
            i--;
            dispatchUidsChangedForObserver(mUidObservers.getBroadcastItem(i),
                    (UidObserverRegistration) mUidObservers.getBroadcastCookie(i), numUidChanges);
        }
        mUidObservers.finishBroadcast();

        if (VALIDATE_UID_STATES && mUidObservers.getRegisteredCallbackCount() > 0) {
            for (int j = 0; j < numUidChanges; ++j) {
                final UidRecord.ChangeItem item = mActiveUidChanges[j];
                if ((item.change & UidRecord.CHANGE_GONE) != 0) {
                    mValidateUids.remove(item.uid);
                } else {
                    UidRecord validateUid = mValidateUids.get(item.uid);
                    if (validateUid == null) {
                        validateUid = new UidRecord(item.uid);
                        mValidateUids.put(item.uid, validateUid);
                    }
                    if ((item.change & UidRecord.CHANGE_IDLE) != 0) {
                        validateUid.idle = true;
                    } else if ((item.change & UidRecord.CHANGE_ACTIVE) != 0) {
                        validateUid.idle = false;
                    }
                    validateUid.setCurProcState(validateUid.setProcState = item.processState);
                    validateUid.curCapability = validateUid.setCapability = item.capability;
                    validateUid.lastDispatchedProcStateSeq = item.procStateSeq;
                }
            }
        }

        synchronized (mService) {
            for (int j = 0; j < numUidChanges; j++) {
                mAvailUidChanges.add(mActiveUidChanges[j]);
            }
        }
    }

    private void dispatchUidsChangedForObserver(IUidObserver observer,
            UidObserverRegistration reg, int changesSize) {
        if (observer == null) {
            return;
        }
        try {
            for (int j = 0; j < changesSize; j++) {
                UidRecord.ChangeItem item = mActiveUidChanges[j];
                final int change = item.change;
                if (change == UidRecord.CHANGE_PROCSTATE
                        && (reg.mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) == 0) {
                    // No-op common case: no significant change, the observer is not
                    // interested in all proc state changes.
                    continue;
                }
                final long start = SystemClock.uptimeMillis();
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
                    if ((reg.mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "UID CHANGED uid=" + item.uid
                                    + ": " + item.processState + ": " + item.capability);
                        }
                        boolean doReport = true;
                        if (reg.mCutpoint >= ActivityManager.MIN_PROCESS_STATE) {
                            final int lastState = reg.mLastProcStates.get(item.uid,
                                    ActivityManager.PROCESS_STATE_UNKNOWN);
                            if (lastState != ActivityManager.PROCESS_STATE_UNKNOWN) {
                                final boolean lastAboveCut = lastState <= reg.mCutpoint;
                                final boolean newAboveCut = item.processState <= reg.mCutpoint;
                                doReport = lastAboveCut != newAboveCut;
                            } else {
                                doReport = item.processState != PROCESS_STATE_NONEXISTENT;
                            }
                        }
                        if (doReport) {
                            if (reg.mLastProcStates != null) {
                                reg.mLastProcStates.put(item.uid, item.processState);
                            }
                            observer.onUidStateChanged(item.uid, item.processState,
                                    item.procStateSeq, item.capability);
                        }
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

    private boolean isEphemeralLocked(int uid) {
        final String[] packages = mService.mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1) { // Ephemeral apps cannot share uid
            return false;
        }
        return mService.getPackageManagerInternalLocked().isPackageEphemeral(
                UserHandle.getUserId(uid), packages[0]);
    }

    @GuardedBy("mService")
    void dump(PrintWriter pw, String dumpPackage) {
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
                pw.print("    "); UserHandle.formatUid(pw, reg.mUid);
                pw.print(" "); pw.print(reg.mPkg);
                final IUidObserver observer = mUidObservers.getRegisteredCallbackItem(i);
                pw.print(" "); pw.print(observer.getClass().getTypeName()); pw.print(":");
                if ((reg.mWhich & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                    pw.print(" IDLE");
                }
                if ((reg.mWhich & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                    pw.print(" ACT");
                }
                if ((reg.mWhich & ActivityManager.UID_OBSERVER_GONE) != 0) {
                    pw.print(" GONE");
                }
                if ((reg.mWhich & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                    pw.print(" STATE");
                    pw.print(" (cut="); pw.print(reg.mCutpoint);
                    pw.print(")");
                }
                pw.println();
                if (reg.mLastProcStates != null) {
                    final int size = reg.mLastProcStates.size();
                    for (int j = 0; j < size; j++) {
                        pw.print("      Last ");
                        UserHandle.formatUid(pw, reg.mLastProcStates.keyAt(j));
                        pw.print(": "); pw.println(reg.mLastProcStates.valueAt(j));
                    }
                }
            }
        }

        pw.println();
        pw.print("  mUidChangeDispatchCount=");
        pw.print(mUidChangeDispatchCount);
        pw.println();
        pw.println("  Slow UID dispatches:");
        final int size = mUidObservers.beginBroadcast();
        for (int i = 0; i < size; i++) {
            UidObserverRegistration r =
                    (UidObserverRegistration) mUidObservers.getBroadcastCookie(i);
            pw.print("    ");
            pw.print(mUidObservers.getBroadcastItem(i).getClass().getTypeName());
            pw.print(": ");
            pw.print(r.mSlowDispatchCount);
            pw.print(" / Max ");
            pw.print(r.mMaxDispatchTime);
            pw.println("ms");
        }
        mUidObservers.finishBroadcast();
    }

    @GuardedBy("mService")
    void dumpDebug(ProtoOutputStream proto, String dumpPackage) {
        final int count = mUidObservers.getRegisteredCallbackCount();
        for (int i = 0; i < count; i++) {
            final UidObserverRegistration reg = (UidObserverRegistration)
                    mUidObservers.getRegisteredCallbackCookie(i);
            if (dumpPackage == null || dumpPackage.equals(reg.mPkg)) {
                reg.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.UID_OBSERVERS);
            }
        }
    }

    private static final class UidObserverRegistration {
        final int mUid;
        final String mPkg;
        final int mWhich;
        final int mCutpoint;

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
        };
        private static final int[] PROTO_ENUMS = new int[]{
                ActivityManagerProto.UID_OBSERVER_FLAG_IDLE,
                ActivityManagerProto.UID_OBSERVER_FLAG_ACTIVE,
                ActivityManagerProto.UID_OBSERVER_FLAG_GONE,
                ActivityManagerProto.UID_OBSERVER_FLAG_PROCSTATE,
        };

        UidObserverRegistration(int uid, String pkg, int which, int cutpoint) {
            this.mUid = uid;
            this.mPkg = pkg;
            this.mWhich = which;
            this.mCutpoint = cutpoint;
            if (cutpoint >= ActivityManager.MIN_PROCESS_STATE) {
                mLastProcStates = new SparseIntArray();
            } else {
                mLastProcStates = null;
            }
        }

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
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

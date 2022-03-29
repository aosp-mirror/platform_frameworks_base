/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.Manifest;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.server.am.UidObserverController.ChangeRecord;

import java.util.function.Consumer;

/**
 * Overall information about a uid that has actively running processes.
 */
public final class UidRecord {
    private final ActivityManagerService mService;
    private final ActivityManagerGlobalLock mProcLock;
    private final int mUid;

    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurProcState;

    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;

    @CompositeRWLock({"mService", "mProcLock"})
    private int mCurCapability;

    @CompositeRWLock({"mService", "mProcLock"})
    private int mSetCapability;

    @CompositeRWLock({"mService", "mProcLock"})
    private long mLastBackgroundTime;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mEphemeral;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mForegroundServices;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mCurAllowList;;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mSetAllowList;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mIdle;

    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mSetIdle;

    @CompositeRWLock({"mService", "mProcLock"})
    private int mNumProcs;

    @CompositeRWLock({"mService", "mProcLock"})
    private ArraySet<ProcessRecord> mProcRecords = new ArraySet<>();

    /**
     * Sequence number associated with the {@link #mCurProcState}. This is incremented using
     * {@link ActivityManagerService#mProcStateSeqCounter}
     * when {@link #mCurProcState} changes from background to foreground or vice versa.
     */
    @GuardedBy("networkStateUpdate")
    long curProcStateSeq;

    /**
     * Last seq number for which NetworkPolicyManagerService notified ActivityManagerService that
     * network policies rules were updated.
     */
    @GuardedBy("networkStateUpdate")
    long lastNetworkUpdatedProcStateSeq;

    /**
     * Last seq number for which AcitivityManagerService dispatched uid state change to
     * NetworkPolicyManagerService.
     */
    @GuardedBy("networkStateUpdate")
    long lastDispatchedProcStateSeq;

    /**
     * Indicates if any thread is waiting for network rules to get updated for {@link #uid}.
     */
    volatile boolean waitingForNetwork;

    /**
     * Indicates whether this uid has internet permission or not.
     */
    volatile boolean hasInternetPermission;

    /**
     * This object is used for waiting for the network state to get updated.
     */
    final Object networkStateLock = new Object();

    /*
     * Change bitmask flags.
     */
    static final int CHANGE_GONE = 1 << 0;
    static final int CHANGE_IDLE = 1 << 1;
    static final int CHANGE_ACTIVE = 1 << 2;
    static final int CHANGE_CACHED = 1 << 3;
    static final int CHANGE_UNCACHED = 1 << 4;
    static final int CHANGE_CAPABILITY = 1 << 5;
    static final int CHANGE_PROCSTATE = 1 << 31;

    // Keep the enum lists in sync
    private static int[] ORIG_ENUMS = new int[] {
            CHANGE_GONE,
            CHANGE_IDLE,
            CHANGE_ACTIVE,
            CHANGE_CACHED,
            CHANGE_UNCACHED,
            CHANGE_CAPABILITY,
            CHANGE_PROCSTATE,
    };
    private static int[] PROTO_ENUMS = new int[] {
            UidRecordProto.CHANGE_GONE,
            UidRecordProto.CHANGE_IDLE,
            UidRecordProto.CHANGE_ACTIVE,
            UidRecordProto.CHANGE_CACHED,
            UidRecordProto.CHANGE_UNCACHED,
            UidRecordProto.CHANGE_CAPABILITY,
            UidRecordProto.CHANGE_PROCSTATE,
    };

    // UidObserverController is the only thing that should modify this.
    final ChangeRecord pendingChange = new ChangeRecord();

    @GuardedBy("mService")
    private int mLastReportedChange;

    public UidRecord(int uid, ActivityManagerService service) {
        mUid = uid;
        mService = service;
        mProcLock = service != null ? service.mProcLock : null;
        mIdle = true;
        reset();
    }

    int getUid() {
        return mUid;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurProcState() {
        return mCurProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurProcState(int curProcState) {
        mCurProcState = curProcState;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetProcState() {
        return mSetProcState;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetProcState(int setProcState) {
        mSetProcState = setProcState;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurCapability() {
        return mCurCapability;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurCapability(int curCapability) {
        mCurCapability = curCapability;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetCapability() {
        return mSetCapability;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetCapability(int setCapability) {
        mSetCapability = setCapability;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getLastBackgroundTime() {
        return mLastBackgroundTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setLastBackgroundTime(long lastBackgroundTime) {
        mLastBackgroundTime = lastBackgroundTime;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isEphemeral() {
        return mEphemeral;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setEphemeral(boolean ephemeral) {
        mEphemeral = ephemeral;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean hasForegroundServices() {
        return mForegroundServices;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setForegroundServices(boolean foregroundServices) {
        mForegroundServices = foregroundServices;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isCurAllowListed() {
        return mCurAllowList;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCurAllowListed(boolean curAllowList) {
        mCurAllowList = curAllowList;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isSetAllowListed() {
        return mSetAllowList;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetAllowListed(boolean setAllowlist) {
        mSetAllowList = setAllowlist;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isIdle() {
        return mIdle;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setIdle(boolean idle) {
        mIdle = idle;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isSetIdle() {
        return mSetIdle;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setSetIdle(boolean setIdle) {
        mSetIdle = setIdle;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getNumOfProcs() {
        return mProcRecords.size();
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void forEachProcess(Consumer<ProcessRecord> callback) {
        for (int i = mProcRecords.size() - 1; i >= 0; i--) {
            callback.accept(mProcRecords.valueAt(i));
        }
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ProcessRecord getProcessInPackage(String packageName) {
        for (int i = mProcRecords.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mProcRecords.valueAt(i);
            if (app != null && TextUtils.equals(app.info.packageName, packageName)) {
                return app;
            }
        }
        return null;
    }

    @GuardedBy({"mService", "mProcLock"})
    void addProcess(ProcessRecord app) {
        mProcRecords.add(app);
    }

    @GuardedBy({"mService", "mProcLock"})
    void removeProcess(ProcessRecord app) {
        mProcRecords.remove(app);
    }

    @GuardedBy("mService")
    void setLastReportedChange(int lastReportedChange) {
        mLastReportedChange = lastReportedChange;
    }

    @GuardedBy({"mService", "mProcLock"})
    void reset() {
        setCurProcState(ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        mForegroundServices = false;
        mCurCapability = 0;
    }

    public void updateHasInternetPermission() {
        hasInternetPermission = ActivityManager.checkUidPermission(Manifest.permission.INTERNET,
                mUid) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * If the change being dispatched is not CHANGE_GONE (not interested in
     * these changes), then update the {@link #lastDispatchedProcStateSeq} with
     * {@link #curProcStateSeq}.
     */
    public void updateLastDispatchedProcStateSeq(int changeToDispatch) {
        if ((changeToDispatch & CHANGE_GONE) == 0) {
            lastDispatchedProcStateSeq = curProcStateSeq;
        }
    }


    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(UidRecordProto.UID, mUid);
        proto.write(UidRecordProto.CURRENT, ProcessList.makeProcStateProtoEnum(mCurProcState));
        proto.write(UidRecordProto.EPHEMERAL, mEphemeral);
        proto.write(UidRecordProto.FG_SERVICES, mForegroundServices);
        proto.write(UidRecordProto.WHILELIST, mCurAllowList);
        ProtoUtils.toDuration(proto, UidRecordProto.LAST_BACKGROUND_TIME,
                mLastBackgroundTime, SystemClock.elapsedRealtime());
        proto.write(UidRecordProto.IDLE, mIdle);
        if (mLastReportedChange != 0) {
            ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, UidRecordProto.LAST_REPORTED_CHANGES,
                    mLastReportedChange, ORIG_ENUMS, PROTO_ENUMS);
        }
        proto.write(UidRecordProto.NUM_PROCS, mNumProcs);

        long seqToken = proto.start(UidRecordProto.NETWORK_STATE_UPDATE);
        proto.write(UidRecordProto.ProcStateSequence.CURURENT, curProcStateSeq);
        proto.write(UidRecordProto.ProcStateSequence.LAST_NETWORK_UPDATED,
                lastNetworkUpdatedProcStateSeq);
        proto.write(UidRecordProto.ProcStateSequence.LAST_DISPATCHED, lastDispatchedProcStateSeq);
        proto.end(seqToken);

        proto.end(token);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("UidRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        UserHandle.formatUid(sb, mUid);
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(mCurProcState));
        if (mEphemeral) {
            sb.append(" ephemeral");
        }
        if (mForegroundServices) {
            sb.append(" fgServices");
        }
        if (mCurAllowList) {
            sb.append(" allowlist");
        }
        if (mLastBackgroundTime > 0) {
            sb.append(" bg:");
            TimeUtils.formatDuration(SystemClock.elapsedRealtime() - mLastBackgroundTime, sb);
        }
        if (mIdle) {
            sb.append(" idle");
        }
        if (mLastReportedChange != 0) {
            sb.append(" change:");
            boolean printed = false;
            if ((mLastReportedChange & CHANGE_GONE) != 0) {
                printed = true;
                sb.append("gone");
            }
            if ((mLastReportedChange & CHANGE_IDLE) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("idle");
            }
            if ((mLastReportedChange & CHANGE_ACTIVE) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("active");
            }
            if ((mLastReportedChange & CHANGE_CACHED) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("cached");
            }
            if ((mLastReportedChange & CHANGE_UNCACHED) != 0) {
                if (printed) {
                    sb.append("|");
                }
                sb.append("uncached");
            }
            if ((mLastReportedChange & CHANGE_PROCSTATE) != 0) {
                if (printed) {
                    sb.append("|");
                }
                sb.append("procstate");
            }
        }
        sb.append(" procs:");
        sb.append(mNumProcs);
        sb.append(" seq(");
        sb.append(curProcStateSeq);
        sb.append(",");
        sb.append(lastNetworkUpdatedProcStateSeq);
        sb.append(",");
        sb.append(lastDispatchedProcStateSeq);
        sb.append(")}");
        return sb.toString();
    }
}

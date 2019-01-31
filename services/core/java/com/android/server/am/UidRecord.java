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
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.GuardedBy;

/**
 * Overall information about a uid that has actively running processes.
 */
public final class UidRecord {
    final int uid;
    private int mCurProcState;
    int setProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
    long lastBackgroundTime;
    boolean ephemeral;
    boolean foregroundServices;
    boolean curWhitelist;
    boolean setWhitelist;
    boolean idle;
    boolean setIdle;
    int numProcs;

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

    static final int CHANGE_PROCSTATE = 0;
    static final int CHANGE_GONE = 1<<0;
    static final int CHANGE_IDLE = 1<<1;
    static final int CHANGE_ACTIVE = 1<<2;
    static final int CHANGE_CACHED = 1<<3;
    static final int CHANGE_UNCACHED = 1<<4;

    // Keep the enum lists in sync
    private static int[] ORIG_ENUMS = new int[] {
            CHANGE_GONE,
            CHANGE_IDLE,
            CHANGE_ACTIVE,
            CHANGE_CACHED,
            CHANGE_UNCACHED,
    };
    private static int[] PROTO_ENUMS = new int[] {
            UidRecordProto.CHANGE_GONE,
            UidRecordProto.CHANGE_IDLE,
            UidRecordProto.CHANGE_ACTIVE,
            UidRecordProto.CHANGE_CACHED,
            UidRecordProto.CHANGE_UNCACHED,
    };

    static final class ChangeItem {
        UidRecord uidRecord;
        int uid;
        int change;
        int processState;
        boolean ephemeral;
        long procStateSeq;
    }

    ChangeItem pendingChange;
    int lastReportedChange;

    public UidRecord(int _uid) {
        uid = _uid;
        idle = true;
        reset();
    }

    public int getCurProcState() {
        return mCurProcState;
    }

    public void setCurProcState(int curProcState) {
        mCurProcState = curProcState;
    }

    public void reset() {
        setCurProcState(ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        foregroundServices = false;
    }

    public void updateHasInternetPermission() {
        hasInternetPermission = ActivityManager.checkUidPermission(Manifest.permission.INTERNET,
                uid) == PackageManager.PERMISSION_GRANTED;
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


    void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(UidRecordProto.UID, uid);
        proto.write(UidRecordProto.CURRENT, ProcessList.makeProcStateProtoEnum(mCurProcState));
        proto.write(UidRecordProto.EPHEMERAL, ephemeral);
        proto.write(UidRecordProto.FG_SERVICES, foregroundServices);
        proto.write(UidRecordProto.WHILELIST, curWhitelist);
        ProtoUtils.toDuration(proto, UidRecordProto.LAST_BACKGROUND_TIME,
                lastBackgroundTime, SystemClock.elapsedRealtime());
        proto.write(UidRecordProto.IDLE, idle);
        if (lastReportedChange != 0) {
            ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, UidRecordProto.LAST_REPORTED_CHANGES,
                    lastReportedChange, ORIG_ENUMS, PROTO_ENUMS);
        }
        proto.write(UidRecordProto.NUM_PROCS, numProcs);

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
        UserHandle.formatUid(sb, uid);
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(mCurProcState));
        if (ephemeral) {
            sb.append(" ephemeral");
        }
        if (foregroundServices) {
            sb.append(" fgServices");
        }
        if (curWhitelist) {
            sb.append(" whitelist");
        }
        if (lastBackgroundTime > 0) {
            sb.append(" bg:");
            TimeUtils.formatDuration(SystemClock.elapsedRealtime()-lastBackgroundTime, sb);
        }
        if (idle) {
            sb.append(" idle");
        }
        if (lastReportedChange != 0) {
            sb.append(" change:");
            boolean printed = false;
            if ((lastReportedChange & CHANGE_GONE) != 0) {
                printed = true;
                sb.append("gone");
            }
            if ((lastReportedChange & CHANGE_IDLE) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("idle");
            }
            if ((lastReportedChange & CHANGE_ACTIVE) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("active");
            }
            if ((lastReportedChange & CHANGE_CACHED) != 0) {
                if (printed) {
                    sb.append("|");
                }
                printed = true;
                sb.append("cached");
            }
            if ((lastReportedChange & CHANGE_UNCACHED) != 0) {
                if (printed) {
                    sb.append("|");
                }
                sb.append("uncached");
            }
        }
        sb.append(" procs:");
        sb.append(numProcs);
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

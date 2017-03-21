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

import android.app.ActivityManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.TimeUtils;

/**
 * Overall information about a uid that has actively running processes.
 */
public final class UidRecord {
    final int uid;
    int curProcState;
    int setProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
    long lastBackgroundTime;
    boolean ephemeral;
    boolean curWhitelist;
    boolean setWhitelist;
    boolean idle;
    int numProcs;

    /**
     * Sequence number associated with the {@link #curProcState}. This is incremented using
     * {@link ActivityManagerService#mProcStateSeqCounter}
     * when {@link #curProcState} changes from background to foreground or vice versa.
     */
    long curProcStateSeq;

    /**
     * Last seq number for which NetworkPolicyManagerService notified ActivityManagerService that
     * network policies rules were updated.
     */
    long lastNetworkUpdatedProcStateSeq;

    /**
     * Last seq number for which AcitivityManagerService dispatched uid state change to
     * NetworkPolicyManagerService.
     */
    long lastDispatchedProcStateSeq;

    static final int CHANGE_PROCSTATE = 0;
    static final int CHANGE_GONE = 1;
    static final int CHANGE_GONE_IDLE = 2;
    static final int CHANGE_IDLE = 3;
    static final int CHANGE_ACTIVE = 4;

    static final class ChangeItem {
        UidRecord uidRecord;
        int uid;
        int change;
        int processState;
        boolean ephemeral;
        long procStateSeq;
    }

    ChangeItem pendingChange;

    public UidRecord(int _uid) {
        uid = _uid;
        reset();
    }

    public void reset() {
        curProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
    }

    /**
     * If the change being dispatched is neither CHANGE_GONE nor CHANGE_GONE_IDLE (not interested in
     * these changes), then update the {@link #lastDispatchedProcStateSeq} with
     * {@link #curProcStateSeq}.
     */
    public void updateLastDispatchedProcStateSeq(int changeToDispatch) {
        if (changeToDispatch != CHANGE_GONE && changeToDispatch != CHANGE_GONE_IDLE) {
            lastDispatchedProcStateSeq = curProcStateSeq;
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("UidRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        UserHandle.formatUid(sb, uid);
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(curProcState));
        if (ephemeral) {
            sb.append(" ephemeral");
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
        sb.append(" procs:");
        sb.append(numProcs);
        sb.append(" curProcStateSeq:");
        sb.append(curProcStateSeq);
        sb.append(" lastNetworkUpdatedProcStateSeq:");
        sb.append(lastNetworkUpdatedProcStateSeq);
        sb.append(" lastDispatchedProcStateSeq:");
        sb.append(lastDispatchedProcStateSeq);
        sb.append("}");
        return sb.toString();
    }
}

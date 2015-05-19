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
import android.os.UserHandle;

/**
 * Overall information about a uid that has actively running processes.
 */
public final class UidRecord {
    final int uid;
    int curProcState;
    int setProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
    int numProcs;

    static final class ChangeItem {
        UidRecord uidRecord;
        int uid;
        boolean gone;
        int processState;
    }

    ChangeItem pendingChange;

    public UidRecord(int _uid) {
        uid = _uid;
        reset();
    }

    public void reset() {
        curProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("UidRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        UserHandle.formatUid(sb, uid);
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(curProcState));
        sb.append(" / ");
        sb.append(numProcs);
        sb.append(" procs}");
        return sb.toString();
    }
}

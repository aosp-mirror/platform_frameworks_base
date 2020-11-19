/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import android.app.ActivityManager;
import android.os.UserHandle;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;

/** Class for tracking active uids for running processes. */
final class ActiveUids {

    private ActivityManagerService mService;

    private boolean mPostChangesToAtm;
    private final SparseArray<UidRecord> mActiveUids = new SparseArray<>();

    ActiveUids(ActivityManagerService service, boolean postChangesToAtm) {
        mService = service;
        mPostChangesToAtm = postChangesToAtm;
    }

    void put(int uid, UidRecord value) {
        mActiveUids.put(uid, value);
        if (mPostChangesToAtm) {
            mService.mAtmInternal.onUidActive(uid, value.getCurProcState());
        }
    }

    void remove(int uid) {
        mActiveUids.remove(uid);
        if (mPostChangesToAtm) {
            mService.mAtmInternal.onUidInactive(uid);
        }
    }

    void clear() {
        mActiveUids.clear();
        // It is only called for a temporal container with mPostChangesToAtm == false or test case.
        // So there is no need to notify activity task manager.
    }

    UidRecord get(int uid) {
        return mActiveUids.get(uid);
    }

    int size() {
        return mActiveUids.size();
    }

    UidRecord valueAt(int index) {
        return mActiveUids.valueAt(index);
    }

    int keyAt(int index) {
        return mActiveUids.keyAt(index);
    }

    int indexOfKey(int uid) {
        return mActiveUids.indexOfKey(uid);
    }

    boolean dump(PrintWriter pw, String dumpPackage, int dumpAppId,
            String header, boolean needSep) {
        boolean printed = false;
        for (int i = 0; i < mActiveUids.size(); i++) {
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (dumpPackage != null && UserHandle.getAppId(uidRec.uid) != dumpAppId) {
                continue;
            }
            if (!printed) {
                printed = true;
                if (needSep) {
                    pw.println();
                }
                pw.print("  "); pw.println(header);
            }
            pw.print("    UID "); UserHandle.formatUid(pw, uidRec.uid);
            pw.print(": "); pw.println(uidRec);
            pw.print("      curProcState="); pw.print(uidRec.mCurProcState);
            pw.print(" curCapability=");
            ActivityManager.printCapabilitiesFull(pw, uidRec.curCapability);
            pw.println();
            for (int j = uidRec.procRecords.size() - 1; j >= 0; j--) {
                pw.print("      proc=");
                pw.println(uidRec.procRecords.valueAt(j));
            }
        }
        return printed;
    }

    void dumpProto(ProtoOutputStream proto, String dumpPackage, int dumpAppId, long fieldId) {
        for (int i = 0; i < mActiveUids.size(); i++) {
            UidRecord uidRec = mActiveUids.valueAt(i);
            if (dumpPackage != null && UserHandle.getAppId(uidRec.uid) != dumpAppId) {
                continue;
            }
            uidRec.dumpDebug(proto, fieldId);
        }
    }
}

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

import android.util.SparseArray;

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
        if (mPostChangesToAtm) {
            mService.mAtmInternal.onActiveUidsCleared();
        }
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
}

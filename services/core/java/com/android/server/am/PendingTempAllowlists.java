/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.util.SparseArray;

/** Allowlists of uids to temporarily bypass Power Save mode. */
final class PendingTempAllowlists {

    private ActivityManagerService mService;

    private final SparseArray<ActivityManagerService.PendingTempAllowlist> mPendingTempAllowlist =
            new SparseArray<>();

    PendingTempAllowlists(ActivityManagerService service) {
        mService = service;
    }

    void put(int uid, ActivityManagerService.PendingTempAllowlist value) {
        synchronized (mPendingTempAllowlist) {
            mPendingTempAllowlist.put(uid, value);
        }
    }

    void removeAt(int index) {
        synchronized (mPendingTempAllowlist) {
            mPendingTempAllowlist.removeAt(index);
        }
    }

    ActivityManagerService.PendingTempAllowlist get(int uid) {
        synchronized (mPendingTempAllowlist) {
            return mPendingTempAllowlist.get(uid);
        }
    }

    int size() {
        synchronized (mPendingTempAllowlist) {
            return mPendingTempAllowlist.size();
        }
    }

    ActivityManagerService.PendingTempAllowlist valueAt(int index) {
        synchronized (mPendingTempAllowlist) {
            return mPendingTempAllowlist.valueAt(index);
        }
    }

    int indexOfKey(int key) {
        synchronized (mPendingTempAllowlist) {
            return mPendingTempAllowlist.indexOfKey(key);
        }
    }
}

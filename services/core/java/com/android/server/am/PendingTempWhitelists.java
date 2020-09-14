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

/** Whitelists of uids to temporarily bypass Power Save mode. */
final class PendingTempWhitelists {

    private ActivityManagerService mService;

    private final SparseArray<ActivityManagerService.PendingTempWhitelist> mPendingTempWhitelist =
            new SparseArray<>();

    PendingTempWhitelists(ActivityManagerService service) {
        mService = service;
    }

    void put(int uid, ActivityManagerService.PendingTempWhitelist value) {
        mPendingTempWhitelist.put(uid, value);
        mService.mAtmInternal.onUidAddedToPendingTempAllowlist(uid, value.tag);
    }

    void removeAt(int index) {
        final int uid = mPendingTempWhitelist.keyAt(index);
        mPendingTempWhitelist.removeAt(index);
        mService.mAtmInternal.onUidRemovedFromPendingTempAllowlist(uid);
    }

    ActivityManagerService.PendingTempWhitelist get(int uid) {
        return mPendingTempWhitelist.get(uid);
    }

    int size() {
        return mPendingTempWhitelist.size();
    }

    ActivityManagerService.PendingTempWhitelist valueAt(int index) {
        return mPendingTempWhitelist.valueAt(index);
    }

    int indexOfKey(int key) {
        return mPendingTempWhitelist.indexOfKey(key);
    }
}

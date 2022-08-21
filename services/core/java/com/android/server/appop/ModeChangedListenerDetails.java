/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

/**
 * Data class that encapsulates the mode change listener's details.
 */
final class ModeChangedListenerDetails  {

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    private int mWatchingUid;
    private int mFlags;
    private int mWatchedOpCode;
    private int mCallingUid;
    private int mCallingPid;


    ModeChangedListenerDetails(int watchingUid, int flags, int watchedOpCode, int callingUid,
            int callingPid) {
        mWatchingUid = watchingUid;
        mFlags = flags;
        mWatchedOpCode = watchedOpCode;
        mCallingUid = callingUid;
        mCallingPid = callingPid;
    }

    /**
     * Returns the user id that is watching for the mode change.
     */
    public int getWatchingUid() {
        return mWatchingUid;
    }

    /**
     * Returns the flags associated with the mode change listener.
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Get the app-op whose mode change should trigger the callback.
     */
    public int getWatchedOpCode() {
        return mWatchedOpCode;
    }

    /**
     * Get the user-id that triggered the app-op mode change to be watched.
     */
    public int getCallingUid() {
        return mCallingUid;
    }

    /**
     * Get the process-id that triggered the app-op mode change to be watched.
     */
    public int getCallingPid() {
        return mCallingPid;
    }

    boolean isWatchingUid(int uid) {
        return uid == UID_ANY || mWatchingUid < 0 || mWatchingUid == uid;
    }
}

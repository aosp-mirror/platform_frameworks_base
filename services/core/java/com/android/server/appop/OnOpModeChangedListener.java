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

import android.companion.virtual.VirtualDeviceManager;
import android.os.RemoteException;

import java.util.Objects;

/**
 * Listener for mode changes, encapsulates methods that should be triggered in the event of a mode
 * change.
 */
public abstract class OnOpModeChangedListener {

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    private int mWatchingUid;
    private int mFlags;
    private int mWatchedOpCode;
    private int mCallingUid;
    private int mCallingPid;

    OnOpModeChangedListener(int watchingUid, int flags, int watchedOpCode, int callingUid,
            int callingPid) {
        this.mWatchingUid = watchingUid;
        this.mFlags = flags;
        this.mWatchedOpCode = watchedOpCode;
        this.mCallingUid = callingUid;
        this.mCallingPid = callingPid;
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

    /**
     * returns true if the user id passed in the param is the one that is watching for op mode
     * changed.
     */
    public boolean isWatchingUid(int uid) {
        return uid == UID_ANY || mWatchingUid < 0 || mWatchingUid == uid;
    }

    /**
     * Method that should be triggered when the app-op's mode is changed.
     * @param op app-op whose mode-change is being listened to.
     * @param uid user-is associated with the app-op.
     * @param packageName package name associated with the app-op.
     */
    public abstract void onOpModeChanged(int op, int uid, String packageName)
            throws RemoteException;

    /**
     * Method that should be triggered when the app-op's mode is changed.
     * @param op app-op whose mode-change is being listened to.
     * @param uid user-is associated with the app-op.
     * @param packageName package name associated with the app-op.
     * @param persistentDeviceId device associated with the app-op.
     */
    public void onOpModeChanged(int op, int uid, String packageName, String persistentDeviceId)
            throws RemoteException {
        if (Objects.equals(persistentDeviceId, VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)) {
            onOpModeChanged(op, uid, packageName);
        }
    }

    /**
     * Return human readable string representing the listener.
     */
    public abstract String toString();

}

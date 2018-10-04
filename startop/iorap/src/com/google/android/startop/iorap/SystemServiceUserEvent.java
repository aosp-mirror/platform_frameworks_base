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
 * limitations under the License.
 */

package com.google.android.startop.iorap;

import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Forward user events to iorapd.<br /><br />
 *
 * Knowledge of the logged-in user is reserved to be used to set-up appropriate policies
 * by iorapd (e.g. to handle user default pinned applications changing).
 *
 * @see com.android.server.SystemService
 *
 * @hide
 */
public class SystemServiceUserEvent implements Parcelable {

    /** @see com.android.server.SystemService#onStartUser */
    public static final int TYPE_START_USER = 0;
    /** @see com.android.server.SystemService#onUnlockUser */
    public static final int TYPE_UNLOCK_USER = 1;
    /** @see com.android.server.SystemService#onSwitchUser*/
    public static final int TYPE_SWITCH_USER = 2;
    /** @see com.android.server.SystemService#onStopUser */
    public static final int TYPE_STOP_USER = 3;
    /** @see com.android.server.SystemService#onCleanupUser */
    public static final int TYPE_CLEANUP_USER = 4;
    private static final int TYPE_MAX = TYPE_CLEANUP_USER;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_START_USER,
            TYPE_UNLOCK_USER,
            TYPE_SWITCH_USER,
            TYPE_STOP_USER,
            TYPE_CLEANUP_USER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;
    public final int userHandle;

    public SystemServiceUserEvent(@Type int type, int userHandle) {
        this.type = type;
        this.userHandle = userHandle;
        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
        if (userHandle < 0) {
            throw new IllegalArgumentException("userHandle must be non-negative");
        }
    }

    @Override
    public String toString() {
        return String.format("{type: %d, userHandle: %d}", type, userHandle);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof SystemServiceUserEvent) {
            return equals((SystemServiceUserEvent) other);
        }
        return false;
    }

    private boolean equals(SystemServiceUserEvent other) {
        return type == other.type &&
                userHandle == other.userHandle;
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        out.writeInt(userHandle);
    }

    private SystemServiceUserEvent(Parcel in) {
        this.type = in.readInt();
        this.userHandle = in.readInt();
        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<SystemServiceUserEvent> CREATOR
            = new Parcelable.Creator<SystemServiceUserEvent>() {
        public SystemServiceUserEvent createFromParcel(Parcel in) {
            return new SystemServiceUserEvent(in);
        }

        public SystemServiceUserEvent[] newArray(int size) {
            return new SystemServiceUserEvent[size];
        }
    };
    //</editor-fold>
}

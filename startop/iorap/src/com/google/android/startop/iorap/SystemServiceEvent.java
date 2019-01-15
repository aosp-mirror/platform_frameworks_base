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
 * Forward system service events to iorapd.
 *
 * @see com.android.server.SystemService
 *
 * @hide
 */
public class SystemServiceEvent implements Parcelable {

    /** @see com.android.server.SystemService#onBootPhase */
    public static final int TYPE_BOOT_PHASE = 0;
    /** @see com.android.server.SystemService#onStart */
    public static final int TYPE_START = 1;
    private static final int TYPE_MAX = TYPE_START;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_BOOT_PHASE,
            TYPE_START,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;

    // TODO: do we want to pass the exact build phase enum?

    public SystemServiceEvent(@Type int type) {
        this.type = type;
        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
    }

    @Override
    public String toString() {
        return String.format("{type: %d}", type);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof SystemServiceEvent) {
            return equals((SystemServiceEvent) other);
        }
        return false;
    }

    private boolean equals(SystemServiceEvent other) {
        return type == other.type;
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
    }

    private SystemServiceEvent(Parcel in) {
        this.type = in.readInt();
        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<SystemServiceEvent> CREATOR
            = new Parcelable.Creator<SystemServiceEvent>() {
        public SystemServiceEvent createFromParcel(Parcel in) {
            return new SystemServiceEvent(in);
        }

        public SystemServiceEvent[] newArray(int size) {
            return new SystemServiceEvent[size];
        }
    };
    //</editor-fold>
}

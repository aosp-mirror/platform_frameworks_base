/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Notifications for iorapd specifying when a package is updated by dexopt service.<br /><br />
 *
 * @hide
 */
public class DexOptEvent implements Parcelable {
    public static final int TYPE_PACKAGE_UPDATE = 0;
    private static final int TYPE_MAX = 0;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_PACKAGE_UPDATE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;
    public final String packageName;

    @NonNull
    public static DexOptEvent createPackageUpdate(String packageName) {
        return new DexOptEvent(TYPE_PACKAGE_UPDATE, packageName);
    }

    private DexOptEvent(@Type int type, String packageName) {
        this.type = type;
        this.packageName = packageName;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
        Objects.requireNonNull(packageName, "packageName");
    }

    @Override
    public String toString() {
        return String.format("{DexOptEvent: packageName: %s}", packageName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof DexOptEvent) {
            return equals((DexOptEvent) other);
        }
        return false;
    }

    private boolean equals(DexOptEvent other) {
        return packageName.equals(other.packageName);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        out.writeString(packageName);
    }

    private DexOptEvent(Parcel in) {
        this.type = in.readInt();
        this.packageName = in.readString();

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<DexOptEvent> CREATOR
            = new Parcelable.Creator<DexOptEvent>() {
        public DexOptEvent createFromParcel(Parcel in) {
            return new DexOptEvent(in);
        }

        public DexOptEvent[] newArray(int size) {
            return new DexOptEvent[size];
        }
    };
    //</editor-fold>
}

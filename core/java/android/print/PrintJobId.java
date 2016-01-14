/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.UUID;

/**
 * This class represents the id of a print job.
 */
public final class PrintJobId implements Parcelable {
    private final @NonNull String mValue;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public PrintJobId() {
        this(UUID.randomUUID().toString());
    }

    /**
     * Creates a new instance.
     *
     * @param value The internal value.
     *
     * @hide
     */
    public PrintJobId(@NonNull String value) {
        mValue = value;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mValue.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrintJobId other = (PrintJobId) obj;
        if (!mValue.equals(other.mValue)) {
            return false;
        }
        return true;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mValue);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flattens this id to a string.
     *
     * @return The flattened id.
     *
     * @hide
     */
    public @NonNull String flattenToString() {
        return mValue;
    }

    /**
     * Unflattens a print job id from a string.
     *
     * @param string The string.
     * @return The unflattened id, or null if the string is malformed.
     *
     * @hide
     */
    public static @NonNull PrintJobId unflattenFromString(@NonNull String string) {
        return new PrintJobId(string);
    }

    public static final Parcelable.Creator<PrintJobId> CREATOR =
            new Parcelable.Creator<PrintJobId>() {
        @Override
        public PrintJobId createFromParcel(Parcel parcel) {
            return new PrintJobId(Preconditions.checkNotNull(parcel.readString()));
        }

        @Override
        public PrintJobId[] newArray(int size) {
            return new PrintJobId[size];
        }
    };
}

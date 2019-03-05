/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.content;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

/**
 * Identifier for an unique state in the application.
 *
 * <p>Should be stable across reboots and backup / restore.
 *
 * <p>For example, a chat app could use the context to resume a conversation between 2 users.
 */
// TODO(b/123577059): make sure this is well documented and understandable
public final class LocusId implements Parcelable {

    private final String mId;

    /**
     * Default constructor.
     *
     * @throws IllegalArgumentException if {@code id} is empty or {@code null}.
     */
    public LocusId(@NonNull String id) {
        mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty");
    }

    /**
     * Gets the {@code id} associated with the locus.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mId == null) ? 0 : mId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final LocusId other = (LocusId) obj;
        if (mId == null) {
            if (other.mId != null) return false;
        } else {
            if (!mId.equals(other.mId)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "LocusId[" + getSanitizedId() + "]";
    }

    /** @hide */
    public void dump(@NonNull PrintWriter pw) {
        pw.print("id:"); pw.println(getSanitizedId());
    }

    @NonNull
    private String getSanitizedId() {
        final int size = mId.length();
        return size + "_chars";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<LocusId> CREATOR =
            new Parcelable.Creator<LocusId>() {

        @NonNull
        @Override
        public LocusId createFromParcel(Parcel parcel) {
            return new LocusId(parcel.readString());
        }

        @NonNull
        @Override
        public LocusId[] newArray(int size) {
            return new LocusId[size];
        }
    };
}

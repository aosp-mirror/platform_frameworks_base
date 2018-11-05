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

package android.service.intelligence;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

// TODO(b/111276913): add javadocs / implement equals/hashcode/string
/** @hide */
@SystemApi
public final class InteractionSessionId implements Parcelable {

    private final int mGlobalId;

    // TODO(b/111276913): remove if not needed
    private final int mLocalId;

    /** @hide */
    public InteractionSessionId(int globalId, int localId) {
        mGlobalId = globalId;
        mLocalId = localId;
    }

    /** @hide */
    public int getGlobalId() {
        return mGlobalId;
    }

    /** @hide */
    // TODO(b/111276913): dump to proto as well
    public void dump(PrintWriter pw) {
        pw.print("globalId="); pw.print(mGlobalId);
        pw.print("localId="); pw.print(mLocalId);
    }

    @Override
    public String toString() {
        return "SessionId[globalId=" + mGlobalId + ", localId=" + mLocalId + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mGlobalId);
        parcel.writeInt(mLocalId);
    }

    public static final Parcelable.Creator<InteractionSessionId> CREATOR =
            new Parcelable.Creator<InteractionSessionId>() {

        @Override
        public InteractionSessionId createFromParcel(Parcel parcel) {
            final int globalId = parcel.readInt();
            final int localId = parcel.readInt();
            return new InteractionSessionId(globalId, localId);
        }

        @Override
        public InteractionSessionId[] newArray(int size) {
            return new InteractionSessionId[size];
        }
    };
}

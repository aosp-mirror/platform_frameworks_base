/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.view.autofill;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

/**
 * A unique identifier for an autofill node inside an {@link android.app.Activity}.
 */
public final class AutofillId implements Parcelable {

    /** @hide */
    public static final int NO_SESSION = 0;

    private static final int FLAG_IS_VIRTUAL = 0x1;
    private static final int FLAG_HAS_SESSION = 0x2;

    private final int mViewId;
    private final int mFlags;
    private final int mVirtualId;
    private final int mSessionId;

    /** @hide */
    @TestApi
    public AutofillId(int id) {
        this(/* flags= */ 0, id, View.NO_ID, NO_SESSION);
    }

    /** @hide */
    @TestApi
    public AutofillId(@NonNull AutofillId parent, int virtualChildId) {
        this(FLAG_IS_VIRTUAL, parent.mViewId, virtualChildId, NO_SESSION);
    }

    /** @hide */
    public AutofillId(int parentId, int virtualChildId) {
        this(FLAG_IS_VIRTUAL, parentId, virtualChildId, NO_SESSION);
    }

    /** @hide */
    public AutofillId(@NonNull AutofillId parent, int virtualChildId, int sessionId) {
        this(FLAG_IS_VIRTUAL | FLAG_HAS_SESSION, parent.mViewId, virtualChildId, sessionId);
    }

    private AutofillId(int flags, int parentId, int virtualChildId, int sessionId) {
        mFlags = flags;
        mViewId = parentId;
        mVirtualId = virtualChildId;
        mSessionId = sessionId;
    }


    /** @hide */
    public int getViewId() {
        return mViewId;
    }

    /** @hide */
    public int getVirtualChildId() {
        return mVirtualId;
    }

    /** @hide */
    public boolean isVirtual() {
        return (mFlags & FLAG_IS_VIRTUAL) != 0;
    }

    private boolean hasSession() {
        return (mFlags & FLAG_HAS_SESSION) != 0;
    }

    /** @hide */
    public int getSessionId() {
        return mSessionId;
    }

    /////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mViewId;
        result = prime * result + mVirtualId;
        result = prime * result + mSessionId;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutofillId other = (AutofillId) obj;
        if (mViewId != other.mViewId) return false;
        if (mVirtualId != other.mVirtualId) return false;
        if (mSessionId != other.mSessionId) return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder().append(mViewId);
        if (isVirtual()) {
            builder.append(':').append(mVirtualId);
        }
        if (hasSession()) {
            builder.append('@').append(mSessionId);
        }
        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mViewId);
        parcel.writeInt(mFlags);
        if (isVirtual()) {
            parcel.writeInt(mVirtualId);
        }
        if (hasSession()) {
            parcel.writeInt(mSessionId);
        }
    }

    public static final Parcelable.Creator<AutofillId> CREATOR =
            new Parcelable.Creator<AutofillId>() {
        @Override
        public AutofillId createFromParcel(Parcel source) {
            final int viewId = source.readInt();
            final int flags = source.readInt();
            final int virtualId = (flags & FLAG_IS_VIRTUAL) != 0 ? source.readInt() : View.NO_ID;
            final int sessionId = (flags & FLAG_HAS_SESSION) != 0 ? source.readInt() : NO_SESSION;
            return new AutofillId(flags, viewId, virtualId, sessionId);
        }

        @Override
        public AutofillId[] newArray(int size) {
            return new AutofillId[size];
        }
    };
}

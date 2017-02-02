/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.view.autofill.Helper.DEBUG;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A unique identifier for an auto-fill node inside an {@link android.app.Activity}.
 */
public final class AutoFillId implements Parcelable {

    private int mViewId;
    private boolean mVirtual;
    private int mVirtualId;

    // TODO(b/33197203): use factory and cache values, since they're immutable
    /** @hide */
    public AutoFillId(int id) {
        mVirtual = false;
        mViewId = id;
    }

    /** @hide */
    public AutoFillId(AutoFillId parent, int virtualChildId) {
        mVirtual = true;
        mViewId = parent.mViewId;
        mVirtualId = virtualChildId;
    }

    /** @hide */
    public AutoFillId(int parentId, int virtualChildId) {
        mVirtual = true;
        mViewId = parentId;
        mVirtualId = virtualChildId;
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
        return mVirtual;
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
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutoFillId other = (AutoFillId) obj;
        if (mViewId != other.mViewId) return false;
        if (mVirtualId != other.mVirtualId) return false;
        return true;
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("FieldId [viewId=").append(mViewId);
        if (mVirtual) {
            builder.append(", virtualId=").append(mVirtualId);
        }
        return builder.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mViewId);
        parcel.writeInt(mVirtual ? 1 : 0);
        parcel.writeInt(mVirtualId);
    }

    private AutoFillId(Parcel parcel) {
        mViewId = parcel.readInt();
        mVirtual = parcel.readInt() == 1;
        mVirtualId = parcel.readInt();
    }

    public static final Parcelable.Creator<AutoFillId> CREATOR =
            new Parcelable.Creator<AutoFillId>() {
        @Override
        public AutoFillId createFromParcel(Parcel source) {
            return new AutoFillId(source);
        }

        @Override
        public AutoFillId[] newArray(int size) {
            return new AutoFillId[size];
        }
    };
}

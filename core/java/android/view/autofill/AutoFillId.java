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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 * @deprecated TODO(b/35956626): remove once clients use getAutoFilltype
 */
@Deprecated
public final class AutoFillId implements Parcelable {

    private final AutofillId mRealId;

    /** @hide */
    public AutoFillId(AutofillId daRealId) {
        this.mRealId = daRealId;
    }

    @Override
    public int hashCode() {
        return mRealId.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final AutoFillId other = (AutoFillId) obj;
        return mRealId.equals(other.mRealId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mRealId, 0);
    }

    private AutoFillId(Parcel parcel) {
        mRealId = parcel.readParcelable(null);
    }

    /** @hide */
    public AutofillId getDaRealId() {
        return mRealId;
    }

    /** @hide */
    public static AutoFillId forDaRealId(AutofillId id) {
        return id == null ? null : new AutoFillId(id);
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

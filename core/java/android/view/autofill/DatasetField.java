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

/** @hide */
public final class DatasetField implements Parcelable {

    private final AutoFillId mId;
    private final AutoFillValue mValue;

    DatasetField(AutoFillId id, AutoFillValue value) {
        mId = id;
        mValue = value;
    }

    public AutoFillId getId() {
        return mId;
    }

    public AutoFillValue getValue() {
        return mValue;
    }

    /////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return "DatasetField [id=" + mId + ", value=" + mValue + "]";
    }

    /////////////////////////////////////
    //  Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mId, 0);
        parcel.writeParcelable(mValue, 0);
    }

    private DatasetField(Parcel parcel) {
        mId = parcel.readParcelable(null);
        mValue = parcel.readParcelable(null);
    }

    public static final Parcelable.Creator<DatasetField> CREATOR =
            new Parcelable.Creator<DatasetField>() {
        @Override
        public DatasetField createFromParcel(Parcel source) {
            return new DatasetField(source);
        }

        @Override
        public DatasetField[] newArray(int size) {
            return new DatasetField[size];
        }
    };
}

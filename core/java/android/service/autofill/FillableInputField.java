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

package android.service.autofill;

import android.app.assist.AssistStructure.ViewNode;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a view field that can be auto-filled.
 *
 * <p>Currently only text-fields are supported, so the value of the field can be obtained through
 * {@link #getValue()}.
 *
 * @hide
 */
public final class FillableInputField implements Parcelable {

    private final int mId;
    private final String mValue;

    private FillableInputField(int id, String value) {
        mId = id;
        mValue = value;
    }

    private FillableInputField(Parcel parcel) {
        mId = parcel.readInt();
        mValue = parcel.readString();
    }

    /**
     * Gets the view id as returned by {@link ViewNode#getAutoFillId()}.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the value of this field.
     */
    public String getValue() {
        return mValue;

    }

    @Override
    public String toString() {
        return "[AutoFillField: " + mId + "=" + mValue + "]";
    }

    /**
     * Creates an {@code AutoFillField} for a text field.
     *
     * @param id view id as returned by {@link ViewNode#getAutoFillId()}.
     * @param text value to be auto-filled.
     */
    public static FillableInputField forText(int id, String text) {
        return new FillableInputField(id, text);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mId);
        parcel.writeString(mValue);
    }

    public static final Parcelable.Creator<FillableInputField> CREATOR =
            new Parcelable.Creator<FillableInputField>() {
        @Override
        public FillableInputField createFromParcel(Parcel source) {
            return new FillableInputField(source);
        }

        @Override
        public FillableInputField[] newArray(int size) {
            return new FillableInputField[size];
        }
    };
}

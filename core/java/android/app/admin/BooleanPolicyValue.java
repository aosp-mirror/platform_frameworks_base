/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;

import java.util.Objects;

/**
 * @hide
 */
public final class BooleanPolicyValue extends PolicyValue<Boolean> {

    public BooleanPolicyValue(boolean value) {
        super(value);
    }

    private BooleanPolicyValue(Parcel source) {
        this(source.readBoolean());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BooleanPolicyValue other = (BooleanPolicyValue) o;
        return Objects.equals(getValue(), other.getValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getValue());
    }

    @Override
    public String toString() {
        return "BooleanPolicyValue { mValue= " + getValue() + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(getValue());
    }

    @NonNull
    public static final Creator<BooleanPolicyValue> CREATOR =
            new Creator<BooleanPolicyValue>() {
                @Override
                public BooleanPolicyValue createFromParcel(Parcel source) {
                    return new BooleanPolicyValue(source);
                }

                @Override
                public BooleanPolicyValue[] newArray(int size) {
                    return new BooleanPolicyValue[size];
                }
            };
}

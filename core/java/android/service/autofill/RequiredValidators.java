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

package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Compound validator that only returns {@code true} on {@link #isValid(ValueFinder)} if all
 * of its subvalidators return {@code true} as well.
 *
 * <p>Used to implement an {@code AND} logical operation.
 *
 * @hide
 */
final class RequiredValidators extends InternalValidator {

    @NonNull private final InternalValidator[] mValidators;

    RequiredValidators(@NonNull InternalValidator[] validators) {
        mValidators = Preconditions.checkArrayElementsNotNull(validators, "validators");
    }

    @Override
    public boolean isValid(@NonNull ValueFinder finder) {
        for (InternalValidator validator : mValidators) {
            final boolean valid = validator.isValid(finder);
            if (!valid) return false;
        }
        return true;
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return new StringBuilder("RequiredValidators: [validators=").append(mValidators)
                .append("]")
                .toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelableArray(mValidators, flags);
    }

    public static final Parcelable.Creator<RequiredValidators> CREATOR =
            new Parcelable.Creator<RequiredValidators>() {
        @Override
        public RequiredValidators createFromParcel(Parcel parcel) {
            return new RequiredValidators(parcel
                .readParcelableArray(null, InternalValidator.class));
        }

        @Override
        public RequiredValidators[] newArray(int size) {
            return new RequiredValidators[size];
        }
    };
}

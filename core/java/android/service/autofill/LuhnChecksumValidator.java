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
import android.text.TextUtils;
import android.util.Log;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

/**
 * Validator that returns {@code true} if the number created by concatenating all given fields
 * pass a Luhn algorithm checksum.
 *
 * <p>See {@link SaveInfo.Builder#setValidator(Validator)} for examples.
 */
public final class LuhnChecksumValidator extends InternalValidator implements Parcelable {
    private static final String TAG = "LuhnChecksumValidator";

    private final AutofillId[] mIds;

    /**
      * Default constructor.
      *
      * @param ids id of fields that comprises the number to be checked.
      */
    public LuhnChecksumValidator(@NonNull AutofillId... ids) {
        mIds = Preconditions.checkArrayElementsNotNull(ids, "ids");
    }

    /** @hide */
    @Override
    public boolean isValid(@NonNull ValueFinder finder) {
        if (mIds == null || mIds.length == 0) return false;

        final StringBuilder number = new StringBuilder();
        for (AutofillId id : mIds) {
            final String partialNumber = finder.findByAutofillId(id);
            if (partialNumber == null) {
                if (sDebug) Log.d(TAG, "No partial number for id " + id);
                return false;
            }
            number.append(partialNumber);
        }
        final boolean isValid = TextUtils.isDigitsOnly(number.toString());
        if (sDebug) Log.d(TAG, "Is valid: " + isValid);
        // TODO(b/62534917): proper implementation - copy & paste code from:
        // PaymentUtils.java
        // PaymentUtilsTest.java
        return isValid;
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelableArray(mIds, flags);
    }

    public static final Parcelable.Creator<LuhnChecksumValidator> CREATOR =
            new Parcelable.Creator<LuhnChecksumValidator>() {
        @Override
        public LuhnChecksumValidator createFromParcel(Parcel parcel) {
            return new LuhnChecksumValidator(parcel.readParcelableArray(null, AutofillId.class));
        }

        @Override
        public LuhnChecksumValidator[] newArray(int size) {
            return new LuhnChecksumValidator[size];
        }
    };
}

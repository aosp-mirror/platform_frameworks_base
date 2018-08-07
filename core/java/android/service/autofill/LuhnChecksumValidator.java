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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

import java.util.Arrays;

/**
 * Validator that returns {@code true} if the number created by concatenating all given fields
 * pass a Luhn algorithm checksum. All non-digits are ignored.
 *
 * <p>See {@link SaveInfo.Builder#setValidator(Validator)} for examples.
 */
public final class LuhnChecksumValidator extends InternalValidator implements Validator,
        Parcelable {
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

    /**
     * Checks if the Luhn checksum is valid.
     *
     * @param number The number including the checksum
     */
    private static boolean isLuhnChecksumValid(@NonNull String number) {
        int sum = 0;
        boolean isDoubled = false;

        for (int i = number.length() - 1; i >= 0; i--) {
            final int digit = number.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                // Ignore non-digits
                continue;
            }

            int addend;
            if (isDoubled) {
                addend = digit * 2;
                if (addend > 9) {
                    addend -= 9;
                }
            } else {
                addend = digit;
            }
            sum += addend;
            isDoubled = !isDoubled;
        }

        return sum % 10 == 0;
    }

    /** @hide */
    @Override
    @TestApi
    public boolean isValid(@NonNull ValueFinder finder) {
        if (mIds == null || mIds.length == 0) return false;

        final StringBuilder builder = new StringBuilder();
        for (AutofillId id : mIds) {
            final String partialNumber = finder.findByAutofillId(id);
            if (partialNumber == null) {
                if (sDebug) Log.d(TAG, "No partial number for id " + id);
                return false;
            }
            builder.append(partialNumber);
        }

        final String number = builder.toString();
        boolean valid = isLuhnChecksumValid(number);
        if (sDebug) Log.d(TAG, "isValid(" + number.length() + " chars): " + valid);
        return valid;
    }

    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "LuhnChecksumValidator: [ids=" + Arrays.toString(mIds) + "]";
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

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

import java.util.regex.Pattern;

/**
 * Defines if a field is valid based on a regular expression (regex).
 *
 * <p>See {@link SaveInfo.Builder#setValidator(Validator)} for examples.
 */
public final class RegexValidator extends InternalValidator implements Validator, Parcelable {

    private static final String TAG = "RegexValidator";

    private final AutofillId mId;
    private final Pattern mRegex;

    /**
     * Default constructor.
     *
     * @param id id of the field whose regex is applied to.
     * @param regex regular expression that defines the result of the validator: if the regex
     * matches the contents of the field identified by {@code id}, it returns {@code true};
     * otherwise, it returns {@code false}.
      */
    public RegexValidator(@NonNull AutofillId id, @NonNull Pattern regex) {
        mId = Preconditions.checkNotNull(id);
        mRegex = Preconditions.checkNotNull(regex);
    }

    /** @hide */
    @Override
    @TestApi
    public boolean isValid(@NonNull ValueFinder finder) {
        final String value = finder.findByAutofillId(mId);
        if (value == null) {
            Log.w(TAG, "No view for id " + mId);
            return false;
        }

        final boolean valid = mRegex.matcher(value).matches();
        if (sDebug) Log.d(TAG, "isValid(): " + valid);
        return valid;
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "RegexValidator: [id=" + mId + ", regex=" + mRegex + "]";
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
        parcel.writeParcelable(mId, flags);
        parcel.writeSerializable(mRegex);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RegexValidator> CREATOR =
            new Parcelable.Creator<RegexValidator>() {
        @Override
        public RegexValidator createFromParcel(Parcel parcel) {
            return new RegexValidator(parcel.readParcelable(null),
                    (Pattern) parcel.readSerializable());
        }

        @Override
        public RegexValidator[] newArray(int size) {
            return new RegexValidator[size];
        }
    };
}

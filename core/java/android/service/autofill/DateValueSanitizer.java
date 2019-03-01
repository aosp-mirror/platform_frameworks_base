/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.icu.text.DateFormat;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

import java.util.Date;

/**
 * Sanitizes a date {@link AutofillValue} using a {@link DateFormat}.
 *
 * <p>For example, to sanitize a credit card expiration date to just its month and year:
 *
 * <pre class="prettyprint">
 * new DateValueSanitizer(new java.text.SimpleDateFormat("MM/yyyy");
 * </pre>
 */
public final class DateValueSanitizer extends InternalSanitizer implements Sanitizer, Parcelable {

    private static final String TAG = "DateValueSanitizer";

    private final DateFormat mDateFormat;

    /**
     * Default constructor.
     *
     * @param dateFormat date format applied to the actual date value of an input field.
      */
    public DateValueSanitizer(@NonNull DateFormat dateFormat) {
        mDateFormat = Preconditions.checkNotNull(dateFormat);
    }

    /** @hide */
    @Override
    @TestApi
    @Nullable
    public AutofillValue sanitize(@NonNull AutofillValue value) {
        if (value == null) {
            Log.w(TAG, "sanitize() called with null value");
            return null;
        }
        if (!value.isDate()) {
            if (sDebug) Log.d(TAG, value + " is not a date");
            return null;
        }

        try {
            final Date date = new Date(value.getDateValue());

            // First convert it to string
            final String converted = mDateFormat.format(date);
            if (sDebug) Log.d(TAG, "Transformed " + date + " to " + converted);
            // Then parse it back to date
            final Date sanitized = mDateFormat.parse(converted);
            if (sDebug) Log.d(TAG, "Sanitized to " + sanitized);
            return AutofillValue.forDate(sanitized.getTime());
        } catch (Exception e) {
            Log.w(TAG, "Could not apply " + mDateFormat + " to " + value + ": " + e);
            return null;
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "DateValueSanitizer: [dateFormat=" + mDateFormat + "]";
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
        parcel.writeSerializable(mDateFormat);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DateValueSanitizer> CREATOR =
            new Parcelable.Creator<DateValueSanitizer>() {
        @Override
        public DateValueSanitizer createFromParcel(Parcel parcel) {
            return new DateValueSanitizer((DateFormat) parcel.readSerializable());
        }

        @Override
        public DateValueSanitizer[] newArray(int size) {
            return new DateValueSanitizer[size];
        }
    };
}

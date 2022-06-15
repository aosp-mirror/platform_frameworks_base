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
import android.annotation.TestApi;
import android.icu.text.DateFormat;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

import java.util.Date;

/**
 * Replaces a {@link TextView} child of a {@link CustomDescription} with the contents of a field
 * that is expected to have a {@link AutofillValue#forDate(long) date value}.
 *
 * <p>For example, a transformation to display a credit card expiration date as month/year would be:
 *
 * <pre class="prettyprint">
 * new DateTransformation(ccExpDate, new java.text.SimpleDateFormat("MM/yyyy")
 * </pre>
 */
public final class DateTransformation extends InternalTransformation implements
        Transformation, Parcelable {
    private static final String TAG = "DateTransformation";

    private final AutofillId mFieldId;
    private final DateFormat mDateFormat;

    /**
     * Creates a new transformation.
     *
     * @param id id of the screen field.
     * @param dateFormat object used to transform the date value of the field to a String.
     */
    public DateTransformation(@NonNull AutofillId id, @NonNull DateFormat dateFormat) {
        mFieldId = Preconditions.checkNotNull(id);
        mDateFormat = Preconditions.checkNotNull(dateFormat);
    }

    /** @hide */
    @Override
    @TestApi
    public void apply(@NonNull ValueFinder finder, @NonNull RemoteViews parentTemplate,
            int childViewId) throws Exception {
        final AutofillValue value = finder.findRawValueByAutofillId(mFieldId);
        if (value == null) {
            Log.w(TAG, "No value for id " + mFieldId);
            return;
        }
        if (!value.isDate()) {
            Log.w(TAG, "Value for " + mFieldId + " is not date: " + value);
            return;
        }

        try {
            final Date date = new Date(value.getDateValue());
            final String transformed = mDateFormat.format(date);
            if (sDebug) Log.d(TAG, "Transformed " + date + " to " + transformed);

            parentTemplate.setCharSequence(childViewId, "setText", transformed);
        } catch (Exception e) {
            Log.w(TAG, "Could not apply " + mDateFormat + " to " + value + ": " + e);
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "DateTransformation: [id=" + mFieldId + ", format=" + mDateFormat + "]";
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
        parcel.writeParcelable(mFieldId, flags);
        parcel.writeSerializable(mDateFormat);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DateTransformation> CREATOR =
            new Parcelable.Creator<DateTransformation>() {
        @Override
        public DateTransformation createFromParcel(Parcel parcel) {
            return new DateTransformation(parcel.readParcelable(null),
                    (DateFormat) parcel.readSerializable());
        }

        @Override
        public DateTransformation[] newArray(int size) {
            return new DateTransformation[size];
        }
    };
}

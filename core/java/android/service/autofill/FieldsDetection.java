/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;

/**
 * Class by service to improve autofillable fields detection by tracking the meaning of fields
 * manually edited by the user (when they match values provided by the service).
 *
 * TODO(b/67867469):
 *  - proper javadoc
 *  - unhide / remove testApi
 *  - add FieldsDetection management so service can set it just once and reference it in further
 *    calls to improve performance (and also API to refresh it)
 *  - rename to FieldsDetectionInfo or FieldClassification? (same for CTS tests)
 *  - add FieldsDetectionUnitTest once API is well-defined
 * @hide
 */
@TestApi
public final class FieldsDetection implements Parcelable {

    private final AutofillId mFieldId;
    private final String mRemoteId;
    private final String mValue;

    /**
     * Creates a field detection for just one field / value pair.
     *
     * @param fieldId autofill id of the field in the screen.
     * @param remoteId id used by the service to identify the field later.
     * @param value field value known to the service.
     *
     * TODO(b/67867469):
     *  - proper javadoc
     *  - change signature to allow more fields / values / match methods
     *    - might also need to use a builder, where the constructor is the id for the fieldsdetector
     *    - might need id for values as well
     *  - add @NonNull / check it / add unit tests
     *  - make 'value' input more generic so it can accept distance-based match and other matches
     *  - throw exception if field value is less than X characters (somewhere between 7-10)
     *  - make sure to limit total number of fields to around 10 or so
     *  - use AutofillValue instead of String (so it can compare dates, for example)
     */
    public FieldsDetection(AutofillId fieldId, String remoteId, String value) {
        mFieldId = fieldId;
        mRemoteId = remoteId;
        mValue = value;
    }

    /** @hide */
    public AutofillId getFieldId() {
        return mFieldId;
    }

    /** @hide */
    public String getRemoteId() {
        return mRemoteId;
    }

    /** @hide */
    public String getValue() {
        return mValue;
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        // Cannot disclose remoteId or value because they could contain PII
        return new StringBuilder("FieldsDetection: [field=").append(mFieldId)
                .append(", remoteId_length=").append(mRemoteId.length())
                .append(", value_length=").append(mValue.length())
                .append("]").toString();
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
        parcel.writeString(mRemoteId);
        parcel.writeString(mValue);
    }

    public static final Parcelable.Creator<FieldsDetection> CREATOR =
            new Parcelable.Creator<FieldsDetection>() {
        @Override
        public FieldsDetection createFromParcel(Parcel parcel) {
            // TODO(b/67867469): remove comment below if it does not use a builder at the end
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            return new FieldsDetection(parcel.readParcelable(null), parcel.readString(),
                    parcel.readString());
        }

        @Override
        public FieldsDetection[] newArray(int size) {
            return new FieldsDetection[size];
        }
    };
}

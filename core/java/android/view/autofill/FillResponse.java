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
import static android.view.autofill.Helper.append;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.autofill.AutoFillService;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Response for a
 * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure, Bundle,
 * android.os.CancellationSignal, android.service.autofill.FillCallback)}
 * request.
 *
 * <p>The response typically contains one or more {@link Dataset}s, each representing a set of
 * fields that can be auto-filled together. For example, for a login page with username/password
 * where the user only have one account in the service, the response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("homer")
 *          .setTextFieldValue(id1, "homer")
 *          .setTextFieldValue(id2, "D'OH!")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>If the user had 2 accounts, each with its own user-provided names, the response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Homer's Account")
 *          .setTextFieldValue(id1, "homer")
 *          .setTextFieldValue(id2, "D'OH!")
 *          .build())
 *      .add(new Dataset.Builder("Bart's Account")
 *          .setTextFieldValue(id1, "elbarto")
 *          .setTextFieldValue(id2, "cowabonga")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>If the user does not have any data associated with this {@link Activity} but the service
 * wants to offer the user the option to save the data that was entered, then the service could
 * populate the response with {@code savableIds} instead of {@link Dataset}s:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .addSavableFields(id1, id2)
 *      .build();
 * </pre>
 *
 * <p>Similarly, there might be cases where the user data on the service is enough to populate some
 * fields but not all, and the service would still be interested on saving the other fields. In this
 * scenario, the service could populate the response with both {@link Dataset}s and
 * {@code savableIds}:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .add(new Dataset.Builder("Homer")
 *          .setTextFieldValue(id1, "Homer")                  // first name
 *          .setTextFieldValue(id2, "Simpson")                // last name
 *          .setTextFieldValue(id3, "742 Evergreen Terrace")  // street
 *          .setTextFieldValue(id4, "Springfield")            // city
 *          .build())
 *       .addSavableFields(id5, id6) // state and zipcode
 *       .build();
 *
 * </pre>
 *
 * <p>Notice that the ids that are part of a dataset (ids 1 to 4, in this example) are automatically
 * added to the {@code savableIds} list.
 *
 * <p>If the service has multiple {@link Dataset}s with multiple options for some fields on each
 * dataset (for example, multiple accounts with both a home and work address), then it should
 * "partition" the {@link Activity} in sections and populate the response with just a subset of the
 * data that would fulfill the first section; then once the user fills the first section and taps
 * a field from the next section, the Android system would issue another request for that section,
 * and so on. For example, the first response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Homer")
 *          .setTextFieldValue(id1, "Homer")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .add(new Dataset.Builder("Bart")
 *          .setTextFieldValue(id1, "Bart")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>Then after the user picks the {@code Homer} dataset and taps the {@code Street} field to
 * trigger another auto-fill request, the second response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Home")
 *          .setTextFieldValue(id3, "742 Evergreen Terrace")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .add(new Dataset.Builder("Work")
 *          .setTextFieldValue(id3, "Springfield Nuclear Power Plant")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>Finally, the service can use the {@link FillResponse.Builder#setExtras(Bundle)} and/or
 * {@link Dataset.Builder#setExtras(Bundle)} methods to pass
 * a {@link Bundle} with service-specific data use to identify this response on future calls (like
 * {@link AutoFillService#onSaveRequest(android.app.assist.AssistStructure, Bundle,
 * android.os.CancellationSignal, android.service.autofill.SaveCallback)}) - such bundle will be
 * available as the {@link AutoFillService#EXTRA_RESPONSE_EXTRAS} extra in
 * that method's {@code extras} argument.
 */
public final class FillResponse implements Parcelable {

    private final List<Dataset> mDatasets;
    private final AutoFillId[] mSavableIds;
    private final Bundle mExtras;

    private FillResponse(Builder builder) {
        // TODO(b/33197203): make it immutable?
        mDatasets = builder.mDatasets;
        final int size = builder.mSavableIds.size();
        mSavableIds = new AutoFillId[size];
        int i = 0;
        for (AutoFillId id : builder.mSavableIds) {
            mSavableIds[i++] = id;
        }
        mExtras = builder.mExtras;
    }

    /** @hide */
    public List<Dataset> getDatasets() {
        return mDatasets;
    }

    /** @hide */
    public AutoFillId[] getSavableIds() {
        return mSavableIds;
    }

    /** @hide */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Builder for {@link FillResponse} objects.
     */
    public static final class Builder {
        private final List<Dataset> mDatasets = new ArrayList<>();
        private final Set<AutoFillId> mSavableIds = new HashSet<>();
        private  Bundle mExtras;

        /**
         * Adds a new {@link Dataset} to this response.
         *
         * @throws IllegalArgumentException if a dataset with same {@code name} already exists.
         */
        public Builder addDataset(Dataset dataset) {
            Preconditions.checkNotNull(dataset, "dataset cannot be null");
            // TODO(b/33197203): check if name already exists
            // TODO(b/33197203): check if authId already exists (and update javadoc)
            mDatasets.add(dataset);
            for (DatasetField field : dataset.getFields()) {
                mSavableIds.add(field.getId());
            }
            return this;
        }

        /**
         * Adds ids of additional fields that the service would be interested to save (through
         * {@link AutoFillService#onSaveRequest(android.app.assist.AssistStructure, Bundle,
         * android.os.CancellationSignal, android.service.autofill.SaveCallback)}) but were not
         * indirectly set through {@link #addDataset(Dataset)}.
         *
         * <p>See {@link FillResponse} for examples.
         */
        public Builder addSavableFields(AutoFillId...ids) {
            for (AutoFillId id : ids) {
                mSavableIds.add(id);
            }
            return this;
        }

        /**
         * Sets a {@link Bundle} that will be passed to subsequent calls to {@link AutoFillService}
         * methods such as
         * {@link AutoFillService#onSaveRequest(android.app.assist.AssistStructure, Bundle,
         * android.os.CancellationSignal, android.service.autofill.SaveCallback)}, using
         * {@link AutoFillService#EXTRA_RESPONSE_EXTRAS} as the key.
         *
         * <p>It can be used when to keep service state in between calls.
         */
        public Builder setExtras(Bundle extras) {
            mExtras = Objects.requireNonNull(extras, "extras cannot be null");
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance.
         */
        public FillResponse build() {
            return new FillResponse(this);
        }
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("FillResponse: [datasets=")
                .append(mDatasets).append(", savableIds=").append(Arrays.toString(mSavableIds))
                .append(", extras=");
        append(builder, mExtras);
        return builder.append(']').toString();
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
        parcel.writeList(mDatasets);
        parcel.writeParcelableArray(mSavableIds, 0);
        parcel.writeBundle(mExtras);
    }

    private FillResponse(Parcel parcel) {
        mDatasets = new ArrayList<>();
        parcel.readList(mDatasets, null);
        mSavableIds = parcel.readParcelableArray(null, AutoFillId.class);
        mExtras = parcel.readBundle();
    }

    public static final Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {
        @Override
        public FillResponse createFromParcel(Parcel source) {
            return new FillResponse(source);
        }

        @Override
        public FillResponse[] newArray(int size) {
            return new FillResponse[size];
        }
    };
}

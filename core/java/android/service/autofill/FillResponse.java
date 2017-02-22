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

import static android.view.autofill.Helper.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillManager;
import android.widget.RemoteViews;

import java.util.ArrayList;

/**
 * Response for a {@link
 * AutoFillService#onFillRequest(android.app.assist.AssistStructure,
 * Bundle, android.os.CancellationSignal, FillCallback)}.
 *
 * <p>The response typically contains one or more {@link Dataset}s, each representing a set of
 * fields that can be auto-filled together, and the Android system displays a dataset picker UI
 * affordance that the user must use before the {@link android.app.Activity} is filled with
 * the dataset.
 *
 * <p>For example, for a login page with username/password where the user only has one account in
 * the response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder(createPresentation())
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
 *      .add(new Dataset.Builder(createFirstPresentation())
 *          .setTextFieldValue(id1, "homer")
 *          .setTextFieldValue(id2, "D'OH!")
 *          .build())
 *      .add(new Dataset.Builder(createSecondPresentation())
 *          .setTextFieldValue(id1, "elbarto")
 *          .setTextFieldValue(id2, "cowabonga")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>If the user does not have any data associated with this {@link android.app.Activity} but
 * the service wants to offer the user the option to save the data that was entered, then the
 * service could populate the response with {@code savableIds} instead of {@link Dataset}s:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .addSavableFields(id1, id2)
 *      .build();
 * </pre>
 *
 * <p>Similarly, there might be cases where the user data on the service is enough to populate some
 * fields but not all, and the service would still be interested on saving the other fields. In this
 * scenario, the service could populate the response with both {@link Dataset}s and {@code
 * savableIds}:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .add(new Dataset.Builder(createPresentation())
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
 * <p>If the service has multiple {@link Dataset}s for different sections of the activity,
 * for example, a user section for which there are two datasets followed by an address
 * section for which there are two datasets for each user user, then it should "partition"
 * the activity in sections and populate the response with just a subset of the data that would
 * fulfill the first section (the name in our example); then once the user fills the first
 * section and taps a field from the next section (the address in our example), the Android
 * system would issue another request for that section, and so on. Note that if the user
 * chooses to populate the first section with a service provided dataset, the subsequent request
 * would contain the populated values so you don't try to provide suggestions for the first
 * section but ony for the second one based on the context of what was already filled. For
 * example, the first response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder(createFirstPresentation())
 *          .setTextFieldValue(id1, "Homer")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .add(new Dataset.Builder(createSecondPresentation())
 *          .setTextFieldValue(id1, "Bart")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>Then after the user picks the second dataset and taps the street field to
 * trigger another auto-fill request, the second response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder(createThirdPresentation())
 *          .setTextFieldValue(id3, "742 Evergreen Terrace")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .add(new Dataset.Builder(createFourthPresentation())
 *          .setTextFieldValue(id3, "Springfield Power Plant")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>The service could require user authentication at the {@link FillResponse} or the
 * {@link Dataset} level, prior to auto-filling an activity - see {@link FillResponse.Builder
 * #setAuthentication(IntentSender)} and {@link Dataset.Builder#setAuthentication(IntentSender)}.
 * It is recommended that you encrypt only the sensitive data but leave the labels unencrypted
 * which would allow you to provide a dataset presentation views with labels and if the user
 * chooses one of them challenge the user to authenticate. For example, if the user has a
 * home and a work address the Home and Work labels could be stored unencrypted as they don't
 * have any sensitive data while the address data is in an encrypted storage. If the user
 * chooses Home, then the platform will start your authentication flow. If you encrypt all
 * data and require auth at the response level the user will have to interact with the fill
 * UI to trigger a request for the datasets (as they don't see the presentation views for the
 * possible options) which will start your auth flow and after successfully authenticating
 * the user will be presented with the Home and Work options to pick one. Hence, you have
 * flexibility how to implement your auth while storing labels non-encrypted and data
 * encrypted provides a better user experience.</p>
 */
public final class FillResponse implements Parcelable {

    private final ArrayList<Dataset> mDatasets;
    private final ArraySet<AutoFillId> mSavableIds;
    private final Bundle mExtras;
    private final RemoteViews mPresentation;
    private final IntentSender mAuthentication;

    private FillResponse(@NonNull Builder builder) {
        mDatasets = builder.mDatasets;
        mSavableIds = builder.mSavableIds;
        mExtras = builder.mExtras;
        mPresentation = builder.mPresentation;
        mAuthentication = builder.mAuthentication;
    }

    /** @hide */
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    /** @hide */
    public @Nullable ArrayList<Dataset> getDatasets() {
        return mDatasets;
    }

    /** @hide */
    public @Nullable ArraySet<AutoFillId> getSavableIds() {
        return mSavableIds;
    }

    /** @hide */
    public @Nullable RemoteViews getPresentation() {
        return mPresentation;
    }

    /** @hide */
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /**
     * Builder for {@link FillResponse} objects. You must to provide at least
     * one dataset or set an authentication intent with a presentation view.
     */
    public static final class Builder {
        private ArrayList<Dataset> mDatasets;
        private ArraySet<AutoFillId> mSavableIds;
        private Bundle mExtras;
        private RemoteViews mPresentation;
        private IntentSender mAuthentication;
        private boolean mDestroyed;

        /**
         * Requires a fill response authentication before auto-filling the activity with
         * any data set in this response.
         *
         * <p>This is typically useful when a user interaction is required to unlock their
         * data vault if you encrypt the data set labels and data set data. It is recommended
         * to encrypt only the sensitive data and not the data set labels which would allow
         * auth on the data set level leading to a better user experience. Note that if you
         * use sensitive data as a label, for example an email address, then it should also
         * be encrypted. The provided {@link android.app.PendingIntent intent} must be an
         * activity which implements your authentication flow. Also if you provide an auth
         * intent you also need to specify the presentation view to be shown in the fill UI
         * for the user to trigger your authentication flow.</p>
         *
         * <p>When a user triggers auto-fill, the system launches the provided intent
         * whose extras will have the {@link AutoFillManager#EXTRA_ASSIST_STRUCTURE screen
         * content}. Once you complete your authentication flow you should set the activity
         * result to {@link android.app.Activity#RESULT_OK} and provide the fully populated
         * {@link FillResponse response} by setting it to the {@link
         * AutoFillManager#EXTRA_AUTHENTICATION_RESULT} extra.
         * For example, if you provided an empty {@link FillResponse resppnse} because the
         * user's data was locked and marked that the response needs an authentication then
         * in the response returned if authentication succeeds you need to provide all
         * available data sets some of which may need to be further authenticated, for
         * example a credit card whose CVV needs to be entered.</p>
         *
         * <p>If you provide an authentication intent you must also provide a presentation
         * which is used to visualize visualize the response for triggering the authentication
         * flow.</p>
         *
         * <p></><strong>Note:</strong> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.</p>
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @param presentation The presentation to visualize the response.
         * @return This builder.
         *
         * @see android.app.PendingIntent#getIntentSender()
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication,
                @Nullable RemoteViews presentation) {
            throwIfDestroyed();
            if (authentication == null ^ presentation == null) {
                throw new IllegalArgumentException("authentication and presentation"
                        + " must be both non-null or null");
            }
            mAuthentication = authentication;
            mPresentation = presentation;
            return this;
        }

        /**
         * Adds a new {@link Dataset} to this response.
         *
         * @return This builder.
         */
        public@NonNull Builder addDataset(@Nullable Dataset dataset) {
            throwIfDestroyed();
            if (dataset == null) {
                return this;
            }
            if (mDatasets == null) {
                mDatasets = new ArrayList<>();
            }
            if (!mDatasets.add(dataset)) {
                return this;
            }
            if (dataset.getFieldIds() != null) {
                final int fieldCount = dataset.getFieldIds().size();
                for (int i = 0; i < fieldCount; i++) {
                    final AutoFillId id = dataset.getFieldIds().get(i);
                    if (mSavableIds == null) {
                        mSavableIds = new ArraySet<>();
                    }
                    mSavableIds.add(id);
                }
            }
            return this;
        }

        /**
         * Adds ids of additional fields that the service would be interested to save (through
         * {@link AutoFillService#onSaveRequest(
         * android.app.assist.AssistStructure, Bundle, SaveCallback)})
         * but were not indirectly set through {@link #addDataset(Dataset)}.
         *
         * @param ids The savable ids.
         * @return This builder.
         *
         * @see FillResponse
         */
        public @NonNull Builder addSavableFields(@Nullable AutoFillId... ids) {
            throwIfDestroyed();
            if (ids == null) {
                return this;
            }
            for (AutoFillId id : ids) {
                if (mSavableIds == null) {
                    mSavableIds = new ArraySet<>();
                }
                mSavableIds.add(id);
            }
            return this;
        }

        /**
         * Sets a {@link Bundle} that will be passed to subsequent APIs that
         * manipulate this response. For example, they are passed to subsequent
         * calls to {@link AutoFillService#onFillRequest(
         * android.app.assist.AssistStructure, Bundle, android.os.CancellationSignal,
         * FillCallback)} and {@link AutoFillService#onSaveRequest(
         * android.app.assist.AssistStructure, Bundle, SaveCallback)}.
         *
         * @param extras The response extras.
         * @return This builder.
         */
        public Builder setExtras(Bundle extras) {
            throwIfDestroyed();
            mExtras = extras;
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance. You must provide at least
         * one dataset or some savable ids or an authentication with a presentation
         * view.
         *
         * @return A built response.
         */
        public FillResponse build() {
            throwIfDestroyed();
            if (mAuthentication == null && mDatasets == null && mSavableIds == null) {
                throw new IllegalArgumentException("need to provide at least one"
                        + " data set or savable ids or an authentication with a presentation");
            }
            mDestroyed = true;
            return new FillResponse(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!DEBUG) return super.toString();
        return new StringBuilder(
                "FillResponse: [datasets=").append(mDatasets)
                .append(", savableIds=").append(mSavableIds)
                .append(", hasExtras=").append(mExtras != null)
                .append(", hasPresentation=").append(mPresentation != null)
                .append(", hasAuthentication=").append(mAuthentication != null)
                .toString();
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
        parcel.writeTypedArrayList(mDatasets, flags);
        parcel.writeTypedArraySet(mSavableIds, flags);
        parcel.writeParcelable(mExtras, flags);
        parcel.writeParcelable(mAuthentication, flags);
        parcel.writeParcelable(mPresentation, flags);
    }

    public static final Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {
        @Override
        public FillResponse createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder();
            final ArrayList<Dataset> datasets = parcel.readTypedArrayList(null);
            final int datasetCount = (datasets != null) ? datasets.size() : 0;
            for (int i = 0; i < datasetCount; i++) {
                builder.addDataset(datasets.get(i));
            }
            final ArraySet<AutoFillId> fillIds = parcel.readTypedArraySet(null);
            final int fillIdCount = (fillIds != null) ? fillIds.size() : 0;
            for (int i = 0; i < fillIdCount; i++) {
                builder.addSavableFields(fillIds.valueAt(i));
            }
            builder.setExtras(parcel.readParcelable(null));
            builder.setAuthentication(parcel.readParcelable(null),
                    parcel.readParcelable(null));
            return builder.build();
        }

        @Override
        public FillResponse[] newArray(int size) {
            return new FillResponse[size];
        }
    };
}

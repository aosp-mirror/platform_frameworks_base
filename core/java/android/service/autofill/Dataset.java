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

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * <p>A <code>Dataset</code> object represents a group of fields (key / value pairs) used
 * to autofill parts of a screen.
 *
 * <p>For more information about the role of datasets in the autofill workflow, read
 * <a href="/guide/topics/text/autofill-services">Build autofill services</a> and the
 * <code><a href="/reference/android/service/autofill/AutofillService">AutofillService</a></code>
 * documentation.
 *
 * <a name="BasicUsage"></a>
 * <h3>Basic usage</h3>
 *
 * <p>In its simplest form, a dataset contains one or more fields (comprised of
 * an {@link AutofillId id}, a {@link AutofillValue value}, and an optional filter
 * {@link Pattern regex}); and one or more {@link RemoteViews presentations} for these fields
 * (each field could have its own {@link RemoteViews presentation}, or use the default
 * {@link RemoteViews presentation} associated with the whole dataset).
 *
 * <p>When an autofill service returns datasets in a {@link FillResponse}
 * and the screen input is focused in a view that is present in at least one of these datasets,
 * the Android System displays a UI containing the {@link RemoteViews presentation} of
 * all datasets pairs that have that view's {@link AutofillId}. Then, when the user selects a
 * dataset from the UI, all views in that dataset are autofilled.
 *
 * <a name="Authentication"></a>
 * <h3>Dataset authentication</h3>
 *
 * <p>In a more sophisticated form, the dataset values can be protected until the user authenticates
 * the dataset&mdash;in that case, when a dataset is selected by the user, the Android System
 * launches an intent set by the service to "unlock" the dataset.
 *
 * <p>For example, when a data set contains credit card information (such as number,
 * expiration date, and verification code), you could provide a dataset presentation saying
 * "Tap to authenticate". Then when the user taps that option, you would launch an activity asking
 * the user to enter the credit card code, and if the user enters a valid code, you could then
 * "unlock" the dataset.
 *
 * <p>You can also use authenticated datasets to offer an interactive UI for the user. For example,
 * if the activity being autofilled is an account creation screen, you could use an authenticated
 * dataset to automatically generate a random password for the user.
 *
 * <p>See {@link Dataset.Builder#setAuthentication(IntentSender)} for more details about the dataset
 * authentication mechanism.
 *
 * <a name="Filtering"></a>
 * <h3>Filtering</h3>
 * <p>The autofill UI automatically changes which values are shown based on value of the view
 * anchoring it, following the rules below:
 * <ol>
 *   <li>If the view's {@link android.view.View#getAutofillValue() autofill value} is not
 * {@link AutofillValue#isText() text} or is empty, all datasets are shown.
 *   <li>Datasets that have a filter regex (set through
 * {@link Dataset.Builder#setValue(AutofillId, AutofillValue, Pattern)} or
 * {@link Dataset.Builder#setValue(AutofillId, AutofillValue, Pattern, RemoteViews)}) and whose
 * regex matches the view's text value converted to lower case are shown.
 *   <li>Datasets that do not require authentication, have a field value that is
 * {@link AutofillValue#isText() text} and whose {@link AutofillValue#getTextValue() value} starts
 * with the lower case value of the view's text are shown.
 *   <li>All other datasets are hidden.
 * </ol>
 *
 */
public final class Dataset implements Parcelable {

    private final ArrayList<AutofillId> mFieldIds;
    private final ArrayList<AutofillValue> mFieldValues;
    private final ArrayList<RemoteViews> mFieldPresentations;
    private final ArrayList<DatasetFieldFilter> mFieldFilters;
    private final RemoteViews mPresentation;
    private final IntentSender mAuthentication;
    @Nullable String mId;

    private Dataset(Builder builder) {
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mFieldPresentations = builder.mFieldPresentations;
        mFieldFilters = builder.mFieldFilters;
        mPresentation = builder.mPresentation;
        mAuthentication = builder.mAuthentication;
        mId = builder.mId;
    }

    /** @hide */
    public @Nullable ArrayList<AutofillId> getFieldIds() {
        return mFieldIds;
    }

    /** @hide */
    public @Nullable ArrayList<AutofillValue> getFieldValues() {
        return mFieldValues;
    }

    /** @hide */
    public RemoteViews getFieldPresentation(int index) {
        final RemoteViews customPresentation = mFieldPresentations.get(index);
        return customPresentation != null ? customPresentation : mPresentation;
    }

    /** @hide */
    @Nullable
    public DatasetFieldFilter getFilter(int index) {
        return mFieldFilters.get(index);
    }

    /** @hide */
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /** @hide */
    public boolean isEmpty() {
        return mFieldIds == null || mFieldIds.isEmpty();
    }

    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        final StringBuilder builder = new StringBuilder("Dataset[");
        if (mId == null) {
            builder.append("noId");
        } else {
            // Cannot disclose id because it could contain PII.
            builder.append("id=").append(mId.length()).append("_chars");
        }
        if (mFieldIds != null) {
            builder.append(", fieldIds=").append(mFieldIds);
        }
        if (mFieldValues != null) {
            builder.append(", fieldValues=").append(mFieldValues);
        }
        if (mFieldPresentations != null) {
            builder.append(", fieldPresentations=").append(mFieldPresentations.size());

        }
        if (mFieldFilters != null) {
            builder.append(", fieldFilters=").append(mFieldFilters.size());
        }
        if (mPresentation != null) {
            builder.append(", hasPresentation");
        }
        if (mAuthentication != null) {
            builder.append(", hasAuthentication");
        }
        return builder.append(']').toString();
    }

    /**
     * Gets the id of this dataset.
     *
     * @return The id of this dataset or {@code null} if not set
     *
     * @hide
     */
    public String getId() {
        return mId;
    }

    /**
     * A builder for {@link Dataset} objects. You must provide at least
     * one value for a field or set an authentication intent.
     */
    public static final class Builder {
        private ArrayList<AutofillId> mFieldIds;
        private ArrayList<AutofillValue> mFieldValues;
        private ArrayList<RemoteViews> mFieldPresentations;
        private ArrayList<DatasetFieldFilter> mFieldFilters;
        private RemoteViews mPresentation;
        private IntentSender mAuthentication;
        private boolean mDestroyed;
        @Nullable private String mId;

        /**
         * Creates a new builder.
         *
         * @param presentation The presentation used to visualize this dataset.
         */
        public Builder(@NonNull RemoteViews presentation) {
            Preconditions.checkNotNull(presentation, "presentation must be non-null");
            mPresentation = presentation;
        }

        /**
         * Creates a new builder for a dataset where each field will be visualized independently.
         *
         * <p>When using this constructor, fields must be set through
         * {@link #setValue(AutofillId, AutofillValue, RemoteViews)} or
         * {@link #setValue(AutofillId, AutofillValue, Pattern, RemoteViews)}.
         */
        public Builder() {
        }

        /**
         * Triggers a custom UI before before autofilling the screen with the contents of this
         * dataset.
         *
         * <p><b>Note:</b> Although the name of this method suggests that it should be used just for
         * authentication flow, it can be used for other advanced flows; see {@link AutofillService}
         * for examples.
         *
         * <p>This method is called when you need to provide an authentication
         * UI for the data set. For example, when a data set contains credit card information
         * (such as number, expiration date, and verification code), you can display UI
         * asking for the verification code before filing in the data. Even if the
         * data set is completely populated the system will launch the specified authentication
         * intent and will need your approval to fill it in. Since the data set is "locked"
         * until the user authenticates it, typically this data set name is masked
         * (for example, "VISA....1234"). Typically you would want to store the data set
         * labels non-encrypted and the actual sensitive data encrypted and not in memory.
         * This allows showing the labels in the UI while involving the user if one of
         * the items with these labels is chosen. Note that if you use sensitive data as
         * a label, for example an email address, then it should also be encrypted.</p>
         *
         * <p>When a user triggers autofill, the system launches the provided intent
         * whose extras will have the {@link
         * android.view.autofill.AutofillManager#EXTRA_ASSIST_STRUCTURE screen content},
         * and your {@link android.view.autofill.AutofillManager#EXTRA_CLIENT_STATE client
         * state}. Once you complete your authentication flow you should set the activity
         * result to {@link android.app.Activity#RESULT_OK} and provide the fully populated
         * {@link Dataset dataset} or a fully-populated {@link FillResponse response} by
         * setting it to the {@link
         * android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} extra. If you
         * provide a dataset in the result, it will replace the authenticated dataset and
         * will be immediately filled in. If you provide a response, it will replace the
         * current response and the UI will be refreshed. For example, if you provided
         * credit card information without the CVV for the data set in the {@link FillResponse
         * response} then the returned data set should contain the CVV entry.
         *
         * <p><b>Note:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @return this builder.
         *
         * @see android.app.PendingIntent
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication) {
            throwIfDestroyed();
            mAuthentication = authentication;
            return this;
        }

        /**
         * Sets the id for the dataset so its usage can be tracked.
         *
         * <p>Dataset usage can be tracked for 2 purposes:
         *
         * <ul>
         *   <li>For statistical purposes, the service can call
         * {@link AutofillService#getFillEventHistory()} when handling {@link
         * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
         * calls.
         *   <li>For normal autofill workflow, the service can call
         *   {@link SaveRequest#getDatasetIds()} when handling
         *   {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)} calls.
         * </ul>
         *
         * @param id id for this dataset or {@code null} to unset.
         *
         * @return this builder.
         */
        public @NonNull Builder setId(@Nullable String id) {
            throwIfDestroyed();
            mId = id;
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * <b>Note:</b> Prior to Android {@link android.os.Build.VERSION_CODES#P}, this method would
         * throw an {@link IllegalStateException} if this builder was constructed without a
         * {@link RemoteViews presentation}. Android {@link android.os.Build.VERSION_CODES#P} and
         * higher removed this restriction because datasets used as an
         * {@link android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT
         * authentication result} do not need a presentation. But if you don't set the presentation
         * in the constructor in a dataset that is meant to be shown to the user, the autofill UI
         * for this field will not be displayed.
         *
         * <p><b>Note:</b> On Android {@link android.os.Build.VERSION_CODES#P} and
         * higher, datasets that require authentication can be also be filtered by passing a
         * {@link AutofillValue#forText(CharSequence) text value} as the {@code value} parameter.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @return this builder.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value) {
            throwIfDestroyed();
            setLifeTheUniverseAndEverything(id, value, null, null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it.
         *
         * <p><b>Note:</b> On Android {@link android.os.Build.VERSION_CODES#P} and
         * higher, datasets that require authentication can be also be filtered by passing a
         * {@link AutofillValue#forText(CharSequence) text value} as the  {@code value} parameter.
         *
         * <p>Theme does not work with RemoteViews layout. Avoid hardcoded text color
         * or background color: Autofill on different platforms may have different themes.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value the value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @param presentation the presentation used to visualize this field.
         * @return this builder.
         *
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @NonNull RemoteViews presentation) {
            throwIfDestroyed();
            Preconditions.checkNotNull(presentation, "presentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, null);
            return this;
        }

        /**
         * Sets the value of a field using an <a href="#Filtering">explicit filter</a>.
         *
         * <p>This method is typically used when the dataset requires authentication and the service
         * does not know its value but wants to hide the dataset after the user enters a minimum
         * number of characters. For example, if the dataset represents a credit card number and the
         * service does not want to show the "Tap to authenticate" message until the user tapped
         * 4 digits, in which case the filter would be {@code Pattern.compile("\\d.{4,}")}.
         *
         * <p><b>Note:</b> If the dataset requires authentication but the service knows its text
         * value it's easier to filter by calling {@link #setValue(AutofillId, AutofillValue)} and
         * use the value to filter.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value the value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @param filter regex used to determine if the dataset should be shown in the autofill UI;
         *        when {@code null}, it disables filtering on that dataset (this is the recommended
         *        approach when {@code value} is not {@code null} and field contains sensitive data
         *        such as passwords).
         *
         * @return this builder.
         * @throws IllegalStateException if the builder was constructed without a
         *         {@link RemoteViews presentation}.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter) {
            throwIfDestroyed();
            Preconditions.checkState(mPresentation != null,
                    "Dataset presentation not set on constructor");
            setLifeTheUniverseAndEverything(id, value, null, new DatasetFieldFilter(filter));
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it and a <a href="#Filtering">explicit filter</a>.
         *
         * <p>This method is typically used when the dataset requires authentication and the service
         * does not know its value but wants to hide the dataset after the user enters a minimum
         * number of characters. For example, if the dataset represents a credit card number and the
         * service does not want to show the "Tap to authenticate" message until the user tapped
         * 4 digits, in which case the filter would be {@code Pattern.compile("\\d.{4,}")}.
         *
         * <p><b>Note:</b> If the dataset requires authentication but the service knows its text
         * value it's easier to filter by calling
         * {@link #setValue(AutofillId, AutofillValue, RemoteViews)} and using the value to filter.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value the value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @param filter regex used to determine if the dataset should be shown in the autofill UI;
         *        when {@code null}, it disables filtering on that dataset (this is the recommended
         *        approach when {@code value} is not {@code null} and field contains sensitive data
         *        such as passwords).
         * @param presentation the presentation used to visualize this field.
         *
         * @return this builder.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter, @NonNull RemoteViews presentation) {
            throwIfDestroyed();
            Preconditions.checkNotNull(presentation, "presentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation,
                    new DatasetFieldFilter(filter));
            return this;
        }

        private void setLifeTheUniverseAndEverything(@NonNull AutofillId id,
                @Nullable AutofillValue value, @Nullable RemoteViews presentation,
                @Nullable DatasetFieldFilter filter) {
            Preconditions.checkNotNull(id, "id cannot be null");
            if (mFieldIds != null) {
                final int existingIdx = mFieldIds.indexOf(id);
                if (existingIdx >= 0) {
                    mFieldValues.set(existingIdx, value);
                    mFieldPresentations.set(existingIdx, presentation);
                    mFieldFilters.set(existingIdx, filter);
                    return;
                }
            } else {
                mFieldIds = new ArrayList<>();
                mFieldValues = new ArrayList<>();
                mFieldPresentations = new ArrayList<>();
                mFieldFilters = new ArrayList<>();
            }
            mFieldIds.add(id);
            mFieldValues.add(value);
            mFieldPresentations.add(presentation);
            mFieldFilters.add(filter);
        }

        /**
         * Creates a new {@link Dataset} instance.
         *
         * <p>You should not interact with this builder once this method is called.
         *
         * @throws IllegalStateException if no field was set (through
         * {@link #setValue(AutofillId, AutofillValue)} or
         * {@link #setValue(AutofillId, AutofillValue, RemoteViews)}).
         *
         * @return The built dataset.
         */
        public @NonNull Dataset build() {
            throwIfDestroyed();
            mDestroyed = true;
            if (mFieldIds == null) {
                throw new IllegalStateException("at least one value must be set");
            }
            return new Dataset(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
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
        parcel.writeParcelable(mPresentation, flags);
        parcel.writeTypedList(mFieldIds, flags);
        parcel.writeTypedList(mFieldValues, flags);
        parcel.writeTypedList(mFieldPresentations, flags);
        parcel.writeTypedList(mFieldFilters, flags);
        parcel.writeParcelable(mAuthentication, flags);
        parcel.writeString(mId);
    }

    public static final Creator<Dataset> CREATOR = new Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final RemoteViews presentation = parcel.readParcelable(null);
            final Builder builder = (presentation == null)
                    ? new Builder()
                    : new Builder(presentation);
            final ArrayList<AutofillId> ids =
                    parcel.createTypedArrayList(AutofillId.CREATOR);
            final ArrayList<AutofillValue> values =
                    parcel.createTypedArrayList(AutofillValue.CREATOR);
            final ArrayList<RemoteViews> presentations =
                    parcel.createTypedArrayList(RemoteViews.CREATOR);
            final ArrayList<DatasetFieldFilter> filters =
                    parcel.createTypedArrayList(DatasetFieldFilter.CREATOR);
            for (int i = 0; i < ids.size(); i++) {
                final AutofillId id = ids.get(i);
                final AutofillValue value = values.get(i);
                final RemoteViews fieldPresentation = presentations.get(i);
                final DatasetFieldFilter filter = filters.get(i);
                builder.setLifeTheUniverseAndEverything(id, value, fieldPresentation, filter);
            }
            builder.setAuthentication(parcel.readParcelable(null));
            builder.setId(parcel.readString());
            return builder.build();
        }

        @Override
        public Dataset[] newArray(int size) {
            return new Dataset[size];
        }
    };

    /**
     * Helper class used to indicate when the service explicitly set a {@link Pattern} filter for a
     * dataset field&dash; we cannot use a {@link Pattern} directly because then we wouldn't be
     * able to differentiate whether the service explicitly passed a {@code null} filter to disable
     * filter, or when it called the methods that does not take a filter {@link Pattern}.
     *
     * @hide
     */
    public static final class DatasetFieldFilter implements Parcelable {

        @Nullable
        public final Pattern pattern;

        private DatasetFieldFilter(@Nullable Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public String toString() {
            if (!sDebug) return super.toString();

            // Cannot log pattern because it could contain PII
            return pattern == null ? "null" : pattern.pattern().length() + "_chars";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeSerializable(pattern);
        }

        @SuppressWarnings("hiding")
        public static final Creator<DatasetFieldFilter> CREATOR =
                new Creator<DatasetFieldFilter>() {

            @Override
            public DatasetFieldFilter createFromParcel(Parcel parcel) {
                return new DatasetFieldFilter((Pattern) parcel.readSerializable());
            }

            @Override
            public DatasetFieldFilter[] newArray(int size) {
                return new DatasetFieldFilter[size];
            }
        };
    }
}

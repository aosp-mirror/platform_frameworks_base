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

/**
 * A dataset object represents a group of key/value pairs used to autofill parts of a screen.
 *
 * <p>In its simplest form, a dataset contains one or more key / value pairs (comprised of
 * {@link AutofillId} and {@link AutofillValue} respectively); and one or more
 * {@link RemoteViews presentation} for these pairs (a pair could have its own
 * {@link RemoteViews presentation}, or use the default {@link RemoteViews presentation} associated
 * with the whole dataset). When an autofill service returns datasets in a {@link FillResponse}
 * and the screen input is focused in a view that is present in at least one of these datasets,
 * the Android System displays a UI affordance containing the {@link RemoteViews presentation} of
 * all datasets pairs that have that view's {@link AutofillId}. Then, when the user selects a
 * dataset from the affordance, all views in that dataset are autofilled.
 *
 * <p>In a more sophisticated form, the dataset value can be protected until the user authenticates
 * the dataset - see {@link Dataset.Builder#setAuthentication(IntentSender)}.
 *
 * @see android.service.autofill.AutofillService for more information and examples about the
 * role of datasets in the autofill workflow.
 */
public final class Dataset implements Parcelable {

    private final ArrayList<AutofillId> mFieldIds;
    private final ArrayList<AutofillValue> mFieldValues;
    private final ArrayList<RemoteViews> mFieldPresentations;
    private final RemoteViews mPresentation;
    private final IntentSender mAuthentication;
    @Nullable String mId;

    private Dataset(Builder builder) {
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mFieldPresentations = builder.mFieldPresentations;
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

        return new StringBuilder("Dataset " + mId + " [")
                .append("fieldIds=").append(mFieldIds)
                .append(", fieldValues=").append(mFieldValues)
                .append(", fieldPresentations=")
                .append(mFieldPresentations == null ? 0 : mFieldPresentations.size())
                .append(", hasPresentation=").append(mPresentation != null)
                .append(", hasAuthentication=").append(mAuthentication != null)
                .append(']').toString();
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
         * {@link #setValue(AutofillId, AutofillValue, RemoteViews)}.
         */
        public Builder() {
        }

        /**
         * Requires a dataset authentication before autofilling the activity with this dataset.
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
         * <p><b>NOTE:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @return This builder.
         *
         * @see android.app.PendingIntent
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication) {
            throwIfDestroyed();
            mAuthentication = authentication;
            return this;
        }

        /**
         * Sets the id for the dataset so its usage history can be retrieved later.
         *
         * <p>The id of the last selected dataset can be read from
         * {@link AutofillService#getFillEventHistory()}. If the id is not set it will not be clear
         * if a dataset was selected as {@link AutofillService#getFillEventHistory()} uses
         * {@code null} to indicate that no dataset was selected.
         *
         * @param id id for this dataset or {@code null} to unset.

         * @return This builder.
         */
        public @NonNull Builder setId(@Nullable String id) {
            throwIfDestroyed();

            mId = id;
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs an authentication and you have no access to the value.
         * @return This builder.
         * @throws IllegalStateException if the builder was constructed without a
         * {@link RemoteViews presentation}.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value) {
            throwIfDestroyed();
            if (mPresentation == null) {
                throw new IllegalStateException("Dataset presentation not set on constructor");
            }
            setValueAndPresentation(id, value, null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value value to be auto filled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs an authentication and you have no access to the value.
         *        Filtering matches any user typed string to {@code null} values.
         * @param presentation The presentation used to visualize this field.
         * @return This builder.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @NonNull RemoteViews presentation) {
            throwIfDestroyed();
            Preconditions.checkNotNull(presentation, "presentation cannot be null");
            setValueAndPresentation(id, value, presentation);
            return this;
        }

        private void setValueAndPresentation(AutofillId id, AutofillValue value,
                RemoteViews presentation) {
            Preconditions.checkNotNull(id, "id cannot be null");
            if (mFieldIds != null) {
                final int existingIdx = mFieldIds.indexOf(id);
                if (existingIdx >= 0) {
                    mFieldValues.set(existingIdx, value);
                    mFieldPresentations.set(existingIdx, presentation);
                    return;
                }
            } else {
                mFieldIds = new ArrayList<>();
                mFieldValues = new ArrayList<>();
                mFieldPresentations = new ArrayList<>();
            }
            mFieldIds.add(id);
            mFieldValues.add(value);
            mFieldPresentations.add(presentation);
        }

        /**
         * Creates a new {@link Dataset} instance.
         *
         * <p>You should not interact with this builder once this method is called.
         *
         * <p>It is required that you specify at least one field before calling this method. It's
         * also mandatory to provide a presentation view to visualize the data set in the UI.
         *
         * @return The built dataset.
         */
        public @NonNull Dataset build() {
            throwIfDestroyed();
            mDestroyed = true;
            if (mFieldIds == null) {
                throw new IllegalArgumentException("at least one value must be set");
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
        parcel.writeTypedArrayList(mFieldIds, flags);
        parcel.writeTypedArrayList(mFieldValues, flags);
        parcel.writeParcelableList(mFieldPresentations, flags);
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
            final ArrayList<AutofillId> ids = parcel.readTypedArrayList(null);
            final ArrayList<AutofillValue> values = parcel.readTypedArrayList(null);
            final ArrayList<RemoteViews> presentations = new ArrayList<>();
            parcel.readParcelableList(presentations, null);
            final int idCount = (ids != null) ? ids.size() : 0;
            final int valueCount = (values != null) ? values.size() : 0;
            for (int i = 0; i < idCount; i++) {
                final AutofillId id = ids.get(i);
                final AutofillValue value = (valueCount > i) ? values.get(i) : null;
                final RemoteViews fieldPresentation = presentations.isEmpty() ? null
                        : presentations.get(i);
                builder.setValueAndPresentation(id, value, fieldPresentation);
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
}

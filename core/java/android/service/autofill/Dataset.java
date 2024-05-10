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

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ClipData;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;
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
 * <p>If both the current Input Method and autofill service supports inline suggestions, the Dataset
 * can be shown by the keyboard as a suggestion. To use this feature, the Dataset should contain
 * an {@link InlinePresentation} representing how the inline suggestion UI will be rendered.
 *
 * <a name="FillDialogUI"></a>
 * <h3>Fill Dialog UI</h3>
 *
 * <p>The fill dialog UI is a more conspicuous and efficient interface than dropdown UI. If autofill
 * suggestions are available when the user clicks on a field that supports filling the dialog UI,
 * Autofill will pop up a fill dialog. The dialog will take up a larger area to display the
 * datasets, so it is easy for users to pay attention to the datasets and selecting a dataset.
 * If the user focuses on the view before suggestions are available, will fall back to dropdown UI
 * or inline suggestions.
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
 *   <li>Datasets that have a filter regex (set through {@link Field.Builder#setFilter(Pattern)}
 *   and {@link Dataset.Builder#setField(AutofillId, Field)}) and whose regex matches the view's
 *   text value converted to lower case are shown.
 *   <li>Datasets that do not require authentication, have a field value that is
 * {@link AutofillValue#isText() text} and whose {@link AutofillValue#getTextValue() value} starts
 * with the lower case value of the view's text are shown.
 *   <li>All other datasets are hidden.
 * </ol>
 * <p>Note: If user enters four or more characters, all datasets will be hidden</p>
 *
 */
public final class Dataset implements Parcelable {
    /**
     * This dataset is picked because of unknown reason.
     * @hide
     */
    public static final int PICK_REASON_UNKNOWN = 0;
    /**
     * This dataset is picked because pcc wasn't enabled.
     * @hide
     */
    public static final int PICK_REASON_NO_PCC = 1;
    /**
     * This dataset is picked because provider gave this dataset.
     * @hide
     */
    public static final int PICK_REASON_PROVIDER_DETECTION_ONLY = 2;
    /**
     * This dataset is picked because provider detection was preferred. However, provider also made
     * this dataset available for PCC detected types, so they could've been picked up by PCC
     * detection. This however doesn't imply that this dataset would've been chosen for sure. For
     * eg, if PCC Detection was preferred, and PCC detected other field types, which wasn't
     * applicable to this dataset, it wouldn't have been shown.
     * @hide
     */
    public static final int PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC = 3;
    /**
     * This dataset is picked because of PCC detection was chosen.
     * @hide
     */
    public static final int PICK_REASON_PCC_DETECTION_ONLY = 4;
    /**
     * This dataset is picked because of PCC Detection was preferred. However, Provider also gave
     * this dataset, so if PCC wasn't enabled, this dataset would've been eligible anyway.
     * @hide
     */
    public static final int PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER = 5;

    /**
     * Reason why the dataset was eligible for autofill.
     * @hide
     */
    @IntDef(prefix = { "PICK_REASON_" }, value = {
            PICK_REASON_UNKNOWN,
            PICK_REASON_NO_PCC,
            PICK_REASON_PROVIDER_DETECTION_ONLY,
            PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC,
            PICK_REASON_PCC_DETECTION_ONLY,
            PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatasetEligibleReason{}

    private @DatasetEligibleReason int mEligibleReason;

    private final ArrayList<AutofillId> mFieldIds;
    private final ArrayList<AutofillValue> mFieldValues;
    private final ArrayList<RemoteViews> mFieldPresentations;
    private final ArrayList<RemoteViews> mFieldDialogPresentations;
    private final ArrayList<InlinePresentation> mFieldInlinePresentations;
    private final ArrayList<InlinePresentation> mFieldInlineTooltipPresentations;
    private final ArrayList<DatasetFieldFilter> mFieldFilters;
    private final ArrayList<String> mAutofillDatatypes;

    @Nullable private final ClipData mFieldContent;
    private final RemoteViews mPresentation;
    private final RemoteViews mDialogPresentation;
    @Nullable private final InlinePresentation mInlinePresentation;
    @Nullable private final InlinePresentation mInlineTooltipPresentation;
    private final IntentSender mAuthentication;

    @Nullable private final Bundle mAuthenticationExtras;

    @Nullable String mId;

    /**
     * Constructor to copy the dataset, but replaces the AutofillId with the given input.
     * Useful to modify the field type, and provide autofillId.
     * @hide
     */
    public Dataset(
            ArrayList<AutofillId> fieldIds,
            ArrayList<AutofillValue> fieldValues,
            ArrayList<RemoteViews> fieldPresentations,
            ArrayList<RemoteViews> fieldDialogPresentations,
            ArrayList<InlinePresentation> fieldInlinePresentations,
            ArrayList<InlinePresentation> fieldInlineTooltipPresentations,
            ArrayList<DatasetFieldFilter> fieldFilters,
            ArrayList<String> autofillDatatypes,
            ClipData fieldContent,
            RemoteViews presentation,
            RemoteViews dialogPresentation,
            @Nullable InlinePresentation inlinePresentation,
            @Nullable  InlinePresentation inlineTooltipPresentation,
            @Nullable String id,
            IntentSender authentication) {
        mFieldIds = fieldIds;
        mFieldValues = fieldValues;
        mFieldPresentations = fieldPresentations;
        mFieldDialogPresentations = fieldDialogPresentations;
        mFieldInlinePresentations = fieldInlinePresentations;
        mFieldInlineTooltipPresentations = fieldInlineTooltipPresentations;
        mAutofillDatatypes = autofillDatatypes;
        mFieldFilters = fieldFilters;
        mFieldContent = fieldContent;
        mPresentation = presentation;
        mDialogPresentation = dialogPresentation;
        mInlinePresentation = inlinePresentation;
        mInlineTooltipPresentation = inlineTooltipPresentation;
        mAuthentication = authentication;
        mAuthenticationExtras = null;
        mId = id;
    }

    /**
     * Constructor to copy the dataset, but replaces the AutofillId with the given input.
     * Useful to modify the field type, and provide autofillId.
     * @hide
     */
    public Dataset(Dataset dataset, ArrayList<AutofillId> ids) {
        mFieldIds = ids;
        mFieldValues = dataset.mFieldValues;
        mFieldPresentations = dataset.mFieldPresentations;
        mFieldDialogPresentations = dataset.mFieldDialogPresentations;
        mFieldInlinePresentations = dataset.mFieldInlinePresentations;
        mFieldInlineTooltipPresentations = dataset.mFieldInlineTooltipPresentations;
        mFieldFilters = dataset.mFieldFilters;
        mFieldContent = dataset.mFieldContent;
        mPresentation = dataset.mPresentation;
        mDialogPresentation = dataset.mDialogPresentation;
        mInlinePresentation = dataset.mInlinePresentation;
        mInlineTooltipPresentation = dataset.mInlineTooltipPresentation;
        mAuthentication = dataset.mAuthentication;
        mAuthenticationExtras = dataset.mAuthenticationExtras;
        mId = dataset.mId;
        mAutofillDatatypes = dataset.mAutofillDatatypes;
    }

    private Dataset(Builder builder) {
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mFieldPresentations = builder.mFieldPresentations;
        mFieldDialogPresentations = builder.mFieldDialogPresentations;
        mFieldInlinePresentations = builder.mFieldInlinePresentations;
        mFieldInlineTooltipPresentations = builder.mFieldInlineTooltipPresentations;
        mFieldFilters = builder.mFieldFilters;
        mFieldContent = builder.mFieldContent;
        mPresentation = builder.mPresentation;
        mDialogPresentation = builder.mDialogPresentation;
        mInlinePresentation = builder.mInlinePresentation;
        mInlineTooltipPresentation = builder.mInlineTooltipPresentation;
        mAuthentication = builder.mAuthentication;
        mAuthenticationExtras = builder.mAuthenticationExtras;
        mId = builder.mId;
        mAutofillDatatypes = builder.mAutofillDatatypes;
    }

    /** @hide */
    @TestApi
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    public @Nullable ArrayList<String> getAutofillDatatypes() {
        return mAutofillDatatypes;
    }

    /** @hide */
    @TestApi
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    public @Nullable ArrayList<AutofillId> getFieldIds() {
        return mFieldIds;
    }

    /** @hide */
    @TestApi
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    public @Nullable ArrayList<AutofillValue> getFieldValues() {
        return mFieldValues;
    }

    /** @hide */
    @TestApi
    public @Nullable RemoteViews getFieldPresentation(int index) {
        final RemoteViews customPresentation = mFieldPresentations.get(index);
        return customPresentation != null ? customPresentation : mPresentation;
    }

    /** @hide */
    @TestApi
    public @Nullable RemoteViews getFieldDialogPresentation(int index) {
        final RemoteViews customPresentation = mFieldDialogPresentations.get(index);
        return customPresentation != null ? customPresentation : mDialogPresentation;
    }

    /** @hide */
    @TestApi
    public @Nullable InlinePresentation getFieldInlinePresentation(int index) {
        final InlinePresentation inlinePresentation = mFieldInlinePresentations.get(index);
        return inlinePresentation != null ? inlinePresentation : mInlinePresentation;
    }

    /** @hide */
    @TestApi
    public @Nullable InlinePresentation getFieldInlineTooltipPresentation(int index) {
        final InlinePresentation inlineTooltipPresentation =
                mFieldInlineTooltipPresentations.get(index);
        return inlineTooltipPresentation != null
                ? inlineTooltipPresentation : mInlineTooltipPresentation;
    }

    /** @hide */
    @TestApi
    public @Nullable DatasetFieldFilter getFilter(int index) {
        return mFieldFilters.get(index);
    }

    /**
     * Returns the content to be filled for a non-text suggestion. This is only applicable to
     * augmented autofill. The target field for the content is available via {@link #getFieldIds()}
     * (guaranteed to have a single field id set when the return value here is non-null). See
     * {@link Builder#setContent(AutofillId, ClipData)} for more info.
     *
     * @hide
     */
    @TestApi
    public @Nullable ClipData getFieldContent() {
        return mFieldContent;
    }

    /** @hide */
    @TestApi
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /** @hide */
    @Hide
    public @Nullable Bundle getAuthenticationExtras() {
        return mAuthenticationExtras;
    }

    /** @hide */
    @TestApi
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
        if (mFieldContent != null) {
            builder.append(", fieldContent=").append(mFieldContent);
        }
        if (mFieldPresentations != null) {
            builder.append(", fieldPresentations=").append(mFieldPresentations.size());
        }
        if (mFieldDialogPresentations != null) {
            builder.append(", fieldDialogPresentations=").append(mFieldDialogPresentations.size());
        }
        if (mFieldInlinePresentations != null) {
            builder.append(", fieldInlinePresentations=").append(mFieldInlinePresentations.size());
        }
        if (mFieldInlineTooltipPresentations != null) {
            builder.append(", fieldInlineTooltipInlinePresentations=").append(
                    mFieldInlineTooltipPresentations.size());
        }
        if (mFieldFilters != null) {
            builder.append(", fieldFilters=").append(mFieldFilters.size());
        }
        if (mPresentation != null) {
            builder.append(", hasPresentation");
        }
        if (mDialogPresentation != null) {
            builder.append(", hasDialogPresentation");
        }
        if (mInlinePresentation != null) {
            builder.append(", hasInlinePresentation");
        }
        if (mInlineTooltipPresentation != null) {
            builder.append(", hasInlineTooltipPresentation");
        }
        if (mAuthentication != null) {
            builder.append(", hasAuthentication");
        }
        if (mAuthenticationExtras != null) {
            builder.append(", hasAuthenticationExtras");
        }
        if (mAutofillDatatypes != null) {
            builder.append(", autofillDatatypes=").append(mAutofillDatatypes);
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
    @TestApi
    public @Nullable String getId() {
        return mId;
    }

    /**
     * Sets the reason as to why this dataset is eligible
     * @hide
     */
    public void setEligibleReasonReason(@DatasetEligibleReason int eligibleReason) {
        this.mEligibleReason = eligibleReason;
    }

    /**
     * Get the reason as to why this dataset is eligible.
     * @hide
     */
    public @DatasetEligibleReason int getEligibleReason() {
        return mEligibleReason;
    }

    /**
     * A builder for {@link Dataset} objects. You must provide at least
     * one value for a field or set an authentication intent.
     */
    public static final class Builder {
        private ArrayList<AutofillId> mFieldIds = new ArrayList<>();
        private ArrayList<AutofillValue> mFieldValues = new ArrayList();
        private ArrayList<RemoteViews> mFieldPresentations = new ArrayList();
        private ArrayList<RemoteViews> mFieldDialogPresentations = new ArrayList();
        private ArrayList<InlinePresentation> mFieldInlinePresentations = new ArrayList();
        private ArrayList<InlinePresentation> mFieldInlineTooltipPresentations = new ArrayList();
        private ArrayList<DatasetFieldFilter> mFieldFilters = new ArrayList();
        private ArrayList<String> mAutofillDatatypes = new ArrayList();
        @Nullable private ClipData mFieldContent;
        private RemoteViews mPresentation;
        private RemoteViews mDialogPresentation;
        @Nullable private InlinePresentation mInlinePresentation;
        @Nullable private InlinePresentation mInlineTooltipPresentation;
        private IntentSender mAuthentication;

        private Bundle mAuthenticationExtras;
        private boolean mDestroyed;
        @Nullable private String mId;

        /**
         * Usually, a field will be associated with a single autofill id and/or datatype.
         * There could be null field value corresponding to different autofill ids or datatye
         * values, but the implementation is ok with duplicating that information.
         * This map is just for the purpose of optimization, to reduce the size of the pelled data
         * over the binder transaction.
         */
        private ArrayMap<Field, Integer> mFieldToIndexdMap = new ArrayMap<>();

        /**
         * Creates a new builder.
         *
         * @param presentation The presentation used to visualize this dataset.
         * @deprecated Use {@link #Builder(Presentations)} instead.
         */
        @Deprecated
        public Builder(@NonNull RemoteViews presentation) {
            Objects.requireNonNull(presentation, "presentation must be non-null");
            mPresentation = presentation;
        }

        /**
         * Creates a new builder.
         *
         * <p>Only called by augmented autofill.
         *
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *              as inline suggestions. If the dataset supports inline suggestions,
         *              this should not be null.
         * @hide
         * @deprecated Use {@link #Builder(Presentations)} instead.
         */
        @SystemApi
        @Deprecated
        public Builder(@NonNull InlinePresentation inlinePresentation) {
            Objects.requireNonNull(inlinePresentation, "inlinePresentation must be non-null");
            mInlinePresentation = inlinePresentation;
        }

        /**
         * Creates a new builder.
         *
         * @param presentations The presentations used to visualize this dataset.
         */
        public Builder(@NonNull Presentations presentations) {
            Objects.requireNonNull(presentations, "presentations must be non-null");

            mPresentation = presentations.getMenuPresentation();
            mInlinePresentation = presentations.getInlinePresentation();
            mInlineTooltipPresentation = presentations.getInlineTooltipPresentation();
            mDialogPresentation = presentations.getDialogPresentation();
        }

        /**
         * Creates a new builder for a dataset where each field will be visualized independently.
         *
         * <p>When using this constructor, a presentation must be provided for each field through
         * {@link #setField(AutofillId, Field)}.
         */
        public Builder() {
        }

        /**
         * Sets the {@link InlinePresentation} used to visualize this dataset as inline suggestions.
         * If the dataset supports inline suggestions this should not be null.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #Builder(Presentations)} instead.
         */
        @Deprecated
        public @NonNull Builder setInlinePresentation(
                @NonNull InlinePresentation inlinePresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(inlinePresentation, "inlinePresentation must be non-null");
            mInlinePresentation = inlinePresentation;
            return this;
        }

        /**
         * Visualizes this dataset as inline suggestions.
         *
         * @param inlinePresentation the {@link InlinePresentation} used to visualize this
         *         dataset as inline suggestions. If the dataset supports inline suggestions this
         *         should not be null.
         * @param inlineTooltipPresentation the {@link InlinePresentation} used to show
         *         the tooltip for the {@code inlinePresentation}.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #Builder(Presentations)} instead.
         */
        @Deprecated
        public @NonNull Builder setInlinePresentation(
                @NonNull InlinePresentation inlinePresentation,
                @NonNull InlinePresentation inlineTooltipPresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(inlinePresentation, "inlinePresentation must be non-null");
            Objects.requireNonNull(inlineTooltipPresentation,
                    "inlineTooltipPresentation must be non-null");
            mInlinePresentation = inlinePresentation;
            mInlineTooltipPresentation = inlineTooltipPresentation;
            return this;
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
         * will be immediately filled in. An exception to this behavior is if the original
         * dataset represents a pinned inline suggestion (i.e. any of the field in the dataset
         * has a pinned inline presentation, see {@link InlinePresentation#isPinned()}), then
         * the original dataset will not be replaced,
         * so that it can be triggered as a pending intent again.
         * If you provide a response, it will replace the
         * current response and the UI will be refreshed. For example, if you provided
         * credit card information without the CVV for the data set in the {@link FillResponse
         * response} then the returned data set should contain the CVV entry.
         *
         * <p><b>Note:</b> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.
         *
         * @param authentication Intent to an activity with your authentication flow.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
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
         * Sets extras to be associated with the {@code authentication} intent sender, to be
         * set on the intent that is fired through the intent sender.
         *
         * Autofill providers can set any extras they wish to receive directly on the intent
         * that is used to create the {@code authentication}. This is an internal API, to be
         * used by the platform to associate data with a given dataset. These extras will be
         * merged with the {@code clientState} and sent as part of the fill in intent when
         * the {@code authentication} intentSender is invoked.
         *
         * @hide
         */
        @Hide
        public @NonNull Builder setAuthenticationExtras(@Nullable Bundle authenticationExtra) {
            throwIfDestroyed();
            mAuthenticationExtras = authenticationExtra;
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
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         */
        public @NonNull Builder setId(@Nullable String id) {
            throwIfDestroyed();
            mId = id;
            return this;
        }

        /**
         * Sets the content for a field.
         *
         * <p>Only called by augmented autofill.
         *
         * <p>For a given field, either a {@link AutofillValue value} or content can be filled, but
         * not both. Furthermore, when filling content, only a single field can be filled.
         *
         * <p>The provided {@link ClipData} can contain content URIs (e.g. a URI for an image).
         * The augmented autofill provider setting the content here must itself have at least
         * read permissions to any passed content URIs. If the user accepts the suggestion backed
         * by the content URI(s), the platform will automatically grant read URI permissions to
         * the app being autofilled, just before passing the content URI(s) to it. The granted
         * permissions will be transient and tied to the lifecycle of the activity being filled
         * (when the activity finishes, permissions will automatically be revoked by the platform).
         *
         * @param id id returned by
         * {@link android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param content content to be autofilled. Pass {@code null} if you do not have the content
         * but the target view is a logical part of the dataset. For example, if the dataset needs
         * authentication.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         * @throws IllegalArgumentException if the provided content
         * {@link ClipData.Item#getIntent() contains an intent}
         *
         * @return this builder.
         *
         * @hide
         */
        @TestApi
        @SystemApi
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder setContent(@NonNull AutofillId id, @Nullable ClipData content) {
            throwIfDestroyed();
            if (content != null) {
                for (int i = 0; i < content.getItemCount(); i++) {
                    Preconditions.checkArgument(content.getItemAt(i).getIntent() == null,
                            "Content items cannot contain an Intent: content=" + content);
                }
            }
            setLifeTheUniverseAndEverything(id, null, null, null, null, null, null);
            mFieldContent = content;
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
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value) {
            throwIfDestroyed();
            setLifeTheUniverseAndEverything(id, value, null, null, null, null, null);
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
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @NonNull RemoteViews presentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, null, null, null, null);
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
         *         {@link RemoteViews presentation} or {@link #build()} was already called.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter) {
            throwIfDestroyed();
            Preconditions.checkState(mPresentation != null,
                    "Dataset presentation not set on constructor");
            setLifeTheUniverseAndEverything(
                    id, value, null, null, null, new DatasetFieldFilter(filter), null);
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
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter, @NonNull RemoteViews presentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, null, null,
                    new DatasetFieldFilter(filter), null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it and an {@link InlinePresentation} to visualize it as an inline suggestion.
         *
         * <p><b>Note:</b> If the dataset requires authentication but the service knows its text
         * value it's easier to filter by calling
         * {@link #setValue(AutofillId, AutofillValue, RemoteViews)} and using the value to filter.
         *
         * @param id id returned by {@link
         *        android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value the value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @param presentation the presentation used to visualize this field.
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *        as inline suggestions. If the dataset supports inline suggestions,
         *        this should not be null.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @NonNull RemoteViews presentation, @NonNull InlinePresentation inlinePresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            Objects.requireNonNull(inlinePresentation, "inlinePresentation cannot be null");
            setLifeTheUniverseAndEverything(
                    id, value, presentation, inlinePresentation, null, null, null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it and an {@link InlinePresentation} to visualize it as an inline suggestion.
         *
         * @see #setValue(AutofillId, AutofillValue, RemoteViews, InlinePresentation)
         *
         * @param id id returned by {@link
         *        android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value the value to be autofilled. Pass {@code null} if you do not have the value
         *        but the target view is a logical part of the dataset. For example, if
         *        the dataset needs authentication and you have no access to the value.
         * @param presentation the presentation used to visualize this field.
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *        as inline suggestions. If the dataset supports inline suggestions,
         *        this should not be null.
         * @param inlineTooltipPresentation The {@link InlinePresentation} used to show
         *        the tooltip for the {@code inlinePresentation}.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @NonNull RemoteViews presentation, @NonNull InlinePresentation inlinePresentation,
                @NonNull InlinePresentation inlineTooltipPresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            Objects.requireNonNull(inlinePresentation, "inlinePresentation cannot be null");
            Objects.requireNonNull(inlineTooltipPresentation,
                    "inlineTooltipPresentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, inlinePresentation,
                    inlineTooltipPresentation, null, null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it and a <a href="#Filtering">explicit filter</a>, and an
         * {@link InlinePresentation} to visualize it as an inline suggestion.
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
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *        as inline suggestions. If the dataset supports inline suggestions, this
         *        should not be null.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter, @NonNull RemoteViews presentation,
                @NonNull InlinePresentation inlinePresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            Objects.requireNonNull(inlinePresentation, "inlinePresentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, inlinePresentation, null,
                    new DatasetFieldFilter(filter), null);
            return this;
        }

        /**
         * Sets the value of a field, using a custom {@link RemoteViews presentation} to
         * visualize it and a <a href="#Filtering">explicit filter</a>, and an
         * {@link InlinePresentation} to visualize it as an inline suggestion.
         *
         * @see #setValue(AutofillId, AutofillValue, Pattern, RemoteViews, InlinePresentation)
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
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *        as inline suggestions. If the dataset supports inline suggestions, this
         *        should not be null.
         * @param inlineTooltipPresentation The {@link InlinePresentation} used to show
         *        the tooltip for the {@code inlinePresentation}.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         */
        @Deprecated
        public @NonNull Builder setValue(@NonNull AutofillId id, @Nullable AutofillValue value,
                @Nullable Pattern filter, @NonNull RemoteViews presentation,
                @NonNull InlinePresentation inlinePresentation,
                @NonNull InlinePresentation inlineTooltipPresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(presentation, "presentation cannot be null");
            Objects.requireNonNull(inlinePresentation, "inlinePresentation cannot be null");
            Objects.requireNonNull(inlineTooltipPresentation,
                    "inlineTooltipPresentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, presentation, inlinePresentation,
                    inlineTooltipPresentation, new DatasetFieldFilter(filter), null);
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * Before Android 13, this information could be provided using several overloaded
         * setValue(...) methods. This method replaces those with a Builder pattern.
         * For example, in the old workflow, the app sets a field would be:
         * <pre class="prettyprint">
         *  Dataset.Builder dataset = new Dataset.Builder();
         *  if (filter != null) {
         *      if (presentation != null) {
         *          if (inlinePresentation != null) {
         *              dataset.setValue(id, value, filter, presentation, inlinePresentation)
         *          } else {
         *              dataset.setValue(id, value, filter, presentation);
         *          }
         *      } else {
         *          dataset.setValue(id, value, filter);
         *      }
         *  } else {
         *      if (presentation != null) {
         *          if (inlinePresentation != null) {
         *              dataset.setValue(id, value, presentation, inlinePresentation)
         *          } else {
         *              dataset.setValue(id, value, presentation);
         *          }
         *      } else {
         *          dataset.setValue(id, value);
         *      }
         *  }
         *  </pre>
         * <p>The new workflow would be:
         * <pre class="prettyprint">
         * Field.Builder fieldBuilder = new Field.Builder();
         * if (value != null) {
         *     fieldBuilder.setValue(value);
         * }
         * if (filter != null) {
         *     fieldBuilder.setFilter(filter);
         * }
         * Presentations.Builder presentationsBuilder = new Presentations.Builder();
         * if (presentation != null) {
         *     presentationsBuilder.setMenuPresentation(presentation);
         * }
         * if (inlinePresentation != null) {
         *     presentationsBuilder.setInlinePresentation(inlinePresentation);
         * }
         * if (dialogPresentation != null) {
         *     presentationsBuilder.setDialogPresentation(dialogPresentation);
         * }
         * fieldBuilder.setPresentations(presentationsBuilder.build());
         * dataset.setField(id, fieldBuilder.build());
         * </pre>
         *
         * @see Field
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param field the fill information about the field.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         */
        public @NonNull Builder setField(@NonNull AutofillId id, @Nullable Field field) {
            throwIfDestroyed();

            if (mFieldToIndexdMap.containsKey(field)) {
                int index = mFieldToIndexdMap.get(field);
                if (mFieldIds.get(index) == null) {
                    mFieldIds.set(index, id);
                    return this;
                }
                // if the Autofill Id is already set, ignore and proceed as if setting in a new
                // value.
            }
            int index;
            if (field == null) {
                index = setLifeTheUniverseAndEverything(id, null, null, null, null, null, null);
            } else {
                final DatasetFieldFilter filter = field.getDatasetFieldFilter();
                final Presentations presentations = field.getPresentations();
                if (presentations == null) {
                    index = setLifeTheUniverseAndEverything(id, field.getValue(), null, null, null,
                            filter, null);
                } else {
                    index = setLifeTheUniverseAndEverything(id, field.getValue(),
                            presentations.getMenuPresentation(),
                            presentations.getInlinePresentation(),
                            presentations.getInlineTooltipPresentation(), filter,
                            presentations.getDialogPresentation());
                }
            }
            mFieldToIndexdMap.put(field, index);
            return this;
        }

        /**
         * Adds a field to this Dataset with a specific type. This is used to send back Field
         * information when Autofilling with platform detections is on.
         * Platform detections are on when receiving a populated list from
         * FillRequest#getHints().
         *
         * Populate every field/type known for this user for this app.
         *
         * For example, if getHints() contains "username" and "password",
         * a new Dataset should be created that calls this method twice,
         * one for the username, then another for the password (assuming
         * the only one credential pair is found for the user). If a user
         * has two credential pairs, then two Datasets should be created,
         * and so on.
         *
         * @param hint An autofill hint returned from {@link
         *         FillRequest#getHints()}.
         *
         * @param field the fill information about the field.
         *
         * @throws IllegalStateException if {@link #build()} was already called
         * or this builder also contains AutofillId information
         *
         * @return this builder.
         */
        public @NonNull Dataset.Builder setField(@NonNull String hint, @NonNull Field field) {
            throwIfDestroyed();

            if (mFieldToIndexdMap.containsKey(field)) {
                int index = mFieldToIndexdMap.get(field);
                if (mAutofillDatatypes.get(index) == null) {
                    mAutofillDatatypes.set(index, hint);
                    return this;
                }
                // if the hint is already set, ignore and proceed as if setting in a new hint.
            }

            int index;
            final DatasetFieldFilter filter = field.getDatasetFieldFilter();
            final Presentations presentations = field.getPresentations();
            if (presentations == null) {
                index = setLifeTheUniverseAndEverything(hint, field.getValue(), null, null, null,
                        filter, null);
            } else {
                index = setLifeTheUniverseAndEverything(hint, field.getValue(),
                        presentations.getMenuPresentation(),
                        presentations.getInlinePresentation(),
                        presentations.getInlineTooltipPresentation(), filter,
                        presentations.getDialogPresentation());
            }
            mFieldToIndexdMap.put(field, index);
            return this;
        }

        /**
         * Adds a field to this Dataset that is relevant to all applicable hints. This is used to
         * provide field information when autofill with platform detections is enabled.
         * Platform detections are on when receiving a populated list from
         * FillRequest#getHints().
         *
         * @param field the fill information about the field.
         *
         * @throws IllegalStateException if {@link #build()} was already called
         * or this builder also contains AutofillId information
         *
         * @return this builder.
         */
        public @NonNull Dataset.Builder setFieldForAllHints(@NonNull Field field) {
            return setField(AutofillManager.ANY_HINT, field);
        }

        /**
         * Sets the value of a field with an <a href="#Filtering">explicit filter</a>, and using an
         * {@link InlinePresentation} to visualize it as an inline suggestion.
         *
         * <p>Only called by augmented autofill.
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
         * @param inlinePresentation The {@link InlinePresentation} used to visualize this dataset
         *        as inline suggestions. If the dataset supports inline suggestions, this
         *        should not be null.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return this builder.
         * @deprecated Use {@link #setField(AutofillId, Field)} instead.
         * @hide
         */
        @Deprecated
        @SystemApi
        public @NonNull Builder setFieldInlinePresentation(@NonNull AutofillId id,
                @Nullable AutofillValue value, @Nullable Pattern filter,
                @NonNull InlinePresentation inlinePresentation) {
            throwIfDestroyed();
            Objects.requireNonNull(inlinePresentation, "inlinePresentation cannot be null");
            setLifeTheUniverseAndEverything(id, value, null, inlinePresentation, null,
                    new DatasetFieldFilter(filter), null);
            return this;
        }

        /** Returns the index at which this id was modified or inserted */
        private int setLifeTheUniverseAndEverything(@NonNull String datatype,
                @Nullable AutofillValue value,
                @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation,
                @Nullable InlinePresentation tooltip,
                @Nullable DatasetFieldFilter filter,
                @Nullable RemoteViews dialogPresentation) {
            Objects.requireNonNull(datatype, "datatype cannot be null");
            final int existingIdx = mAutofillDatatypes.indexOf(datatype);
            if (existingIdx >= 0) {
                mAutofillDatatypes.add(datatype);
                mFieldValues.set(existingIdx, value);
                mFieldPresentations.set(existingIdx, presentation);
                mFieldDialogPresentations.set(existingIdx, dialogPresentation);
                mFieldInlinePresentations.set(existingIdx, inlinePresentation);
                mFieldInlineTooltipPresentations.set(existingIdx, tooltip);
                mFieldFilters.set(existingIdx, filter);
                return existingIdx;
            }
            mFieldIds.add(null);
            mAutofillDatatypes.add(datatype);
            mFieldValues.add(value);
            mFieldPresentations.add(presentation);
            mFieldDialogPresentations.add(dialogPresentation);
            mFieldInlinePresentations.add(inlinePresentation);
            mFieldInlineTooltipPresentations.add(tooltip);
            mFieldFilters.add(filter);
            return mFieldIds.size() - 1;
        }

        /** Returns the index at which this id was modified or inserted */
        private int setLifeTheUniverseAndEverything(@NonNull AutofillId id,
                @Nullable AutofillValue value, @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation,
                @Nullable InlinePresentation tooltip,
                @Nullable DatasetFieldFilter filter,
                @Nullable RemoteViews dialogPresentation) {
            Objects.requireNonNull(id, "id cannot be null");
            final int existingIdx = mFieldIds.indexOf(id);
            if (existingIdx >= 0) {
                mFieldValues.set(existingIdx, value);
                mFieldPresentations.set(existingIdx, presentation);
                mFieldDialogPresentations.set(existingIdx, dialogPresentation);
                mFieldInlinePresentations.set(existingIdx, inlinePresentation);
                mFieldInlineTooltipPresentations.set(existingIdx, tooltip);
                mFieldFilters.set(existingIdx, filter);
                return existingIdx;
            }
            mFieldIds.add(id);
            mAutofillDatatypes.add(null);
            mFieldValues.add(value);
            mFieldPresentations.add(presentation);
            mFieldDialogPresentations.add(dialogPresentation);
            mFieldInlinePresentations.add(inlinePresentation);
            mFieldInlineTooltipPresentations.add(tooltip);
            mFieldFilters.add(filter);
            return mFieldIds.size() - 1;
        }

        private void createFromParcel(
                @Nullable AutofillId id, @Nullable String datatype,
                @Nullable AutofillValue value, @Nullable RemoteViews presentation,
                @Nullable InlinePresentation inlinePresentation,
                @Nullable InlinePresentation tooltip,
                @Nullable DatasetFieldFilter filter,
                @Nullable RemoteViews dialogPresentation) {
            if (id != null) {
                final int existingIdx = mFieldIds.indexOf(id);
                if (existingIdx >= 0) {
                    mFieldValues.set(existingIdx, value);
                    mFieldPresentations.set(existingIdx, presentation);
                    mFieldDialogPresentations.set(existingIdx, dialogPresentation);
                    mFieldInlinePresentations.set(existingIdx, inlinePresentation);
                    mFieldInlineTooltipPresentations.set(existingIdx, tooltip);
                    mFieldFilters.set(existingIdx, filter);
                    return;
                }
            }
            mFieldIds.add(id);
            mAutofillDatatypes.add(datatype);
            mFieldValues.add(value);
            mFieldPresentations.add(presentation);
            mFieldDialogPresentations.add(dialogPresentation);
            mFieldInlinePresentations.add(inlinePresentation);
            mFieldInlineTooltipPresentations.add(tooltip);
            mFieldFilters.add(filter);
            return;
        }

        /**
         * Creates a new {@link Dataset} instance.
         *
         * <p>You should not interact with this builder once this method is called.
         *
         * @throws IllegalStateException if no field was set (through
         * {@link #setField(AutofillId, Field)}), or if {@link #build()} was already called.
         *
         * @return The built dataset.
         */
        public @NonNull Dataset build() {
            throwIfDestroyed();
            mDestroyed = true;
            if (mFieldIds == null && mAutofillDatatypes == null) {
                throw new IllegalStateException("at least one of field or datatype must be set");
            }
            if (mFieldIds != null && mAutofillDatatypes != null) {
                if (mFieldIds.size() == 0 && mAutofillDatatypes.size() == 0) {
                    throw new IllegalStateException(
                            "at least one of field or datatype must be set");
                }
            }
            if (mFieldContent != null) {
                if (mFieldIds.size() > 1) {
                    throw new IllegalStateException(
                            "when filling content, only one field can be filled");
                }
                if (mFieldValues.get(0) != null) {
                    throw new IllegalStateException("cannot fill both content and values");
                }
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
        parcel.writeParcelable(mDialogPresentation, flags);
        parcel.writeParcelable(mInlinePresentation, flags);
        parcel.writeParcelable(mInlineTooltipPresentation, flags);
        parcel.writeTypedList(mFieldIds, flags);
        parcel.writeTypedList(mFieldValues, flags);
        parcel.writeTypedList(mFieldPresentations, flags);
        parcel.writeTypedList(mFieldDialogPresentations, flags);
        parcel.writeTypedList(mFieldInlinePresentations, flags);
        parcel.writeTypedList(mFieldInlineTooltipPresentations, flags);
        parcel.writeTypedList(mFieldFilters, flags);
        parcel.writeStringList(mAutofillDatatypes);
        parcel.writeParcelable(mFieldContent, flags);
        parcel.writeParcelable(mAuthentication, flags);
        parcel.writeString(mId);
        parcel.writeInt(mEligibleReason);
        parcel.writeTypedObject(mAuthenticationExtras, flags);
    }

    public static final @NonNull Creator<Dataset> CREATOR = new Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel parcel) {
            final RemoteViews presentation = parcel.readParcelable(null,
                    android.widget.RemoteViews.class);
            final RemoteViews dialogPresentation = parcel.readParcelable(null,
                    android.widget.RemoteViews.class);
            final InlinePresentation inlinePresentation = parcel.readParcelable(null,
                    android.service.autofill.InlinePresentation.class);
            final InlinePresentation inlineTooltipPresentation =
                    parcel.readParcelable(null, android.service.autofill.InlinePresentation.class);
            final ArrayList<AutofillId> ids =
                    parcel.createTypedArrayList(AutofillId.CREATOR);
            final ArrayList<AutofillValue> values =
                    parcel.createTypedArrayList(AutofillValue.CREATOR);
            final ArrayList<RemoteViews> presentations =
                    parcel.createTypedArrayList(RemoteViews.CREATOR);
            final ArrayList<RemoteViews> dialogPresentations =
                    parcel.createTypedArrayList(RemoteViews.CREATOR);
            final ArrayList<InlinePresentation> inlinePresentations =
                    parcel.createTypedArrayList(InlinePresentation.CREATOR);
            final ArrayList<InlinePresentation> inlineTooltipPresentations =
                    parcel.createTypedArrayList(InlinePresentation.CREATOR);
            final ArrayList<DatasetFieldFilter> filters =
                    parcel.createTypedArrayList(DatasetFieldFilter.CREATOR);
            final ArrayList<String> autofillDatatypes =
                    parcel.createStringArrayList();
            final ClipData fieldContent = parcel.readParcelable(null,
                    android.content.ClipData.class);
            final IntentSender authentication = parcel.readParcelable(null,
                    android.content.IntentSender.class);
            final String datasetId = parcel.readString();
            final int eligibleReason = parcel.readInt();
            final Bundle authenticationExtras = parcel.readTypedObject(Bundle.CREATOR);

            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder;
            if (presentation != null || inlinePresentation != null || dialogPresentation != null) {
                final Presentations.Builder presentationsBuilder = new Presentations.Builder();
                if (presentation != null) {
                    presentationsBuilder.setMenuPresentation(presentation);
                }
                if (inlinePresentation != null) {
                    presentationsBuilder.setInlinePresentation(inlinePresentation);
                }
                if (inlineTooltipPresentation != null) {
                    presentationsBuilder.setInlineTooltipPresentation(inlineTooltipPresentation);
                }
                if (dialogPresentation != null) {
                    presentationsBuilder.setDialogPresentation(dialogPresentation);
                }
                builder = new Builder(presentationsBuilder.build());
            } else {
                builder = new Builder();
            }

            if (fieldContent != null) {
                builder.setContent(ids.get(0), fieldContent);
            }
            final int inlinePresentationsSize = inlinePresentations.size();
            for (int i = 0; i < ids.size(); i++) {
                final AutofillId id = ids.get(i);
                final String datatype = autofillDatatypes.get(i);
                final AutofillValue value = values.get(i);
                final RemoteViews fieldPresentation = presentations.get(i);
                final RemoteViews fieldDialogPresentation = dialogPresentations.get(i);
                final InlinePresentation fieldInlinePresentation =
                        i < inlinePresentationsSize ? inlinePresentations.get(i) : null;
                final InlinePresentation fieldInlineTooltipPresentation =
                        i < inlinePresentationsSize ? inlineTooltipPresentations.get(i) : null;
                final DatasetFieldFilter filter = filters.get(i);
                builder.createFromParcel(id, datatype, value, fieldPresentation,
                        fieldInlinePresentation, fieldInlineTooltipPresentation, filter,
                        fieldDialogPresentation);
            }
            builder.setAuthentication(authentication);
            builder.setAuthenticationExtras(authenticationExtras);
            builder.setId(datasetId);
            Dataset dataset = builder.build();
            dataset.mEligibleReason = eligibleReason;
            return dataset;
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
    @TestApi
    public static final class DatasetFieldFilter implements Parcelable {

        /** @hide */
        @Nullable
        public final Pattern pattern;

        DatasetFieldFilter(@Nullable Pattern pattern) {
            this.pattern = pattern;
        }

        public @Nullable Pattern getPattern() {
            return pattern;
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
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeSerializable(pattern);
        }

        @SuppressWarnings("hiding")
        public static final @android.annotation.NonNull Creator<DatasetFieldFilter> CREATOR =
                new Creator<DatasetFieldFilter>() {

            @Override
            public DatasetFieldFilter createFromParcel(Parcel parcel) {
                return new DatasetFieldFilter((Pattern) parcel.readSerializable(java.util.regex.Pattern.class.getClassLoader(), java.util.regex.Pattern.class));
            }

            @Override
            public DatasetFieldFilter[] newArray(int size) {
                return new DatasetFieldFilter[size];
            }
        };
    }
}

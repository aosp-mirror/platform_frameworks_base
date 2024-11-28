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

import static android.service.autofill.Flags.FLAG_AUTOFILL_W_METRICS;
import static android.view.autofill.Helper.sVerbose;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes what happened after the last
 * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
 * call.
 *
 * <p>This history is typically used to keep track of previous user actions to optimize further
 * requests. For example, the service might return email addresses in alphabetical order by
 * default, but change that order based on the address the user picked on previous requests.
 *
 * <p>The history is not persisted over reboots, and it's cleared every time the service
 * replies to a
 * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
 * by calling {@link FillCallback#onSuccess(FillResponse)} or
 * {@link FillCallback#onFailure(CharSequence)} (if the service doesn't call any of these methods,
 * the history will clear out after some pre-defined time).
 */
public final class FillEventHistory implements Parcelable {
    private static final String TAG = "FillEventHistory";

    /**
     * The ID of the autofill session that created the {@link FillResponse}.
     *
     * TODO: add this to the parcel.
     */
    private final int mSessionId;

    @Nullable private final Bundle mClientState;
    @Nullable List<Event> mEvents;

    /**
     * Returns the unique identifier of this FillEventHistory.
     *
     * <p>This is used to differentiate individual FillEventHistory.
     */
    @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Returns the client state set in the previous {@link FillResponse}.
     *
     * <p><b>Note: </b>the state is associated with the app that was autofilled in the previous
     * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
     * , which is not necessary the same app being autofilled now.
     *
     * @deprecated use {@link #getEvents()} then {@link Event#getClientState()} instead.
     */
    @Deprecated
    @Nullable public Bundle getClientState() {
        return mClientState;
    }

    /**
     * Returns the events occurred after the latest call to
     * {@link FillCallback#onSuccess(FillResponse)}.
     *
     * @return The list of events or {@code null} if non occurred.
     */
    @Nullable public List<Event> getEvents() {
        return mEvents;
    }

    /**
     * @hide
     */
    public void addEvent(Event event) {
        if (mEvents == null) {
            mEvents = new ArrayList<>(1);
        }
        mEvents.add(event);
    }

    /**
     * @hide
     */
    public FillEventHistory(int sessionId, @Nullable Bundle clientState) {
        mClientState = clientState;
        mSessionId = sessionId;
    }

    @Override
    public String toString() {
        return mEvents == null ? "no events" : mEvents.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBundle(mClientState);
        if (mEvents == null) {
            parcel.writeInt(0);
        } else {
            parcel.writeInt(mEvents.size());

            int numEvents = mEvents.size();
            for (int i = 0; i < numEvents; i++) {
                Event event = mEvents.get(i);
                parcel.writeInt(event.mEventType);
                parcel.writeString(event.mDatasetId);
                parcel.writeBundle(event.mClientState);
                parcel.writeStringList(event.mSelectedDatasetIds);
                parcel.writeArraySet(event.mIgnoredDatasetIds);
                parcel.writeTypedList(event.mChangedFieldIds);
                parcel.writeStringList(event.mChangedDatasetIds);

                parcel.writeTypedList(event.mManuallyFilledFieldIds);
                if (event.mManuallyFilledFieldIds != null) {
                    final int size = event.mManuallyFilledFieldIds.size();
                    for (int j = 0; j < size; j++) {
                        parcel.writeStringList(event.mManuallyFilledDatasetIds.get(j));
                    }
                }
                final AutofillId[] detectedFields = event.mDetectedFieldIds;
                parcel.writeParcelableArray(detectedFields, flags);
                if (detectedFields != null) {
                    FieldClassification.writeArrayToParcel(parcel,
                            event.mDetectedFieldClassifications);
                }
                parcel.writeInt(event.mSaveDialogNotShowReason);
                parcel.writeInt(event.mUiType);
                if (Flags.addLastFocusedIdToFillEventHistory()) {
                    parcel.writeParcelable(event.mFocusedId, 0);
                }
            }
        }
    }

    /**
     * Description of an event that occurred after the latest call to
     * {@link FillCallback#onSuccess(FillResponse)}.
     */
    public static final class Event {
        /**
         * A dataset was selected. The dataset selected can be read from {@link #getDatasetId()}.
         *
         * <p><b>Note: </b>on Android {@link android.os.Build.VERSION_CODES#O}, this event was also
         * incorrectly reported after a
         * {@link Dataset.Builder#setAuthentication(IntentSender) dataset authentication} was
         * selected and the service returned a dataset in the
         * {@link AutofillManager#EXTRA_AUTHENTICATION_RESULT} of the activity launched from that
         * {@link IntentSender}. This behavior was fixed on Android
         * {@link android.os.Build.VERSION_CODES#O_MR1}.
         */
        public static final int TYPE_DATASET_SELECTED = 0;

        /**
         * A {@link Dataset.Builder#setAuthentication(IntentSender) dataset authentication} was
         * selected. The dataset authenticated can be read from {@link #getDatasetId()}.
         */
        public static final int TYPE_DATASET_AUTHENTICATION_SELECTED = 1;

        /**
         * A {@link FillResponse.Builder#setAuthentication(android.view.autofill.AutofillId[],
         * IntentSender, android.widget.RemoteViews) fill response authentication} was selected.
         */
        public static final int TYPE_AUTHENTICATION_SELECTED = 2;

        /** A save UI was shown. */
        public static final int TYPE_SAVE_SHOWN = 3;

        /**
         * A committed autofill context for which the autofill service provided datasets.
         *
         * <p>This event is useful to track:
         * <ul>
         *   <li>Which datasets (if any) were selected by the user
         *       ({@link #getSelectedDatasetIds()}).
         *   <li>Which datasets (if any) were NOT selected by the user
         *       ({@link #getIgnoredDatasetIds()}).
         *   <li>Which fields in the selected datasets were changed by the user after the dataset
         *       was selected ({@link #getChangedFields()}.
         *   <li>Which fields match the {@link UserData} set by the service.
         * </ul>
         *
         * <p><b>Note: </b>This event is only generated when:
         * <ul>
         *   <li>The autofill context is committed.
         *   <li>The service provides at least one dataset in the
         *       {@link FillResponse fill responses} associated with the context.
         *   <li>The last {@link FillResponse fill responses} associated with the context has the
         *       {@link FillResponse#FLAG_TRACK_CONTEXT_COMMITED} flag.
         * </ul>
         *
         * <p>See {@link android.view.autofill.AutofillManager} for more information about autofill
         * contexts.
         */
        public static final int TYPE_CONTEXT_COMMITTED = 4;

        /**
         * A dataset selector was shown.
         *
         * <p>This event is fired whenever the autofill UI was presented to the user.</p>
         */
        public static final int TYPE_DATASETS_SHOWN = 5;

        /**
         * The app/user requested for a field to be Autofilled.
         *
         * This event is fired when the view has been entered (by user or app) in order
         * to differentiate from FillRequests that have been pretriggered for FillDialogs.
         *
         * For example, the user might navigate away from a screen without tapping any
         * fields. In this case, a FillRequest/FillResponse has been generated, but was
         * not used for Autofilling. The user did not intend to see an Autofill result,
         * but a FillRequest was still generated. This is different from when the user
         * did tap on a field after the pretriggered FillRequest, this event will appear
         * in the FillEventHistory, signaling that the user did intend to Autofill
         * something.
         */
        public static final int TYPE_VIEW_REQUESTED_AUTOFILL = 6;

        /** @hide */
        @IntDef(prefix = { "TYPE_" }, value = {
                TYPE_DATASET_SELECTED,
                TYPE_DATASET_AUTHENTICATION_SELECTED,
                TYPE_AUTHENTICATION_SELECTED,
                TYPE_SAVE_SHOWN,
                TYPE_CONTEXT_COMMITTED,
                TYPE_DATASETS_SHOWN,
                TYPE_VIEW_REQUESTED_AUTOFILL
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface EventIds{}

        /** No reason for save dialog. */
        public static final int NO_SAVE_UI_REASON_NONE = 0;

        /** The SaveInfo associated with the FillResponse is null. */
        public static final int NO_SAVE_UI_REASON_NO_SAVE_INFO = 1;

        /** The service asked to delay save. */
        public static final int NO_SAVE_UI_REASON_WITH_DELAY_SAVE_FLAG = 2;

        /** There was empty value for required ids. */
        public static final int NO_SAVE_UI_REASON_HAS_EMPTY_REQUIRED = 3;

        /** No value has been changed. */
        public static final int NO_SAVE_UI_REASON_NO_VALUE_CHANGED = 4;

        /** Fields failed validation. */
        public static final int NO_SAVE_UI_REASON_FIELD_VALIDATION_FAILED = 5;

        /** All fields matched contents of datasets. */
        public static final int NO_SAVE_UI_REASON_DATASET_MATCH = 6;

        /**
         * Credential Manager is invoked instead of Autofill. When that happens, Save Dialog cannot
         * be shown, and this will be populated in
         */
        @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
        public static final int NO_SAVE_UI_REASON_USING_CREDMAN = 7;

        /** @hide */
        @IntDef(prefix = { "NO_SAVE_UI_REASON_" }, value = {
                NO_SAVE_UI_REASON_NONE,
                NO_SAVE_UI_REASON_NO_SAVE_INFO,
                NO_SAVE_UI_REASON_WITH_DELAY_SAVE_FLAG,
                NO_SAVE_UI_REASON_HAS_EMPTY_REQUIRED,
                NO_SAVE_UI_REASON_NO_VALUE_CHANGED,
                NO_SAVE_UI_REASON_FIELD_VALIDATION_FAILED,
                NO_SAVE_UI_REASON_DATASET_MATCH
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface NoSaveReason{}

        /** The autofill suggestion presentation is unknown, this will be set for the event
         * that is unrelated to fill Ui presentation */
        public static final int UI_TYPE_UNKNOWN = 0;

        /** The autofill suggestion is shown as a menu popup presentation. */
        public static final int UI_TYPE_MENU = 1;

        /** The autofill suggestion is shown as a keyboard inline presentation. */
        public static final int UI_TYPE_INLINE = 2;

        /** The autofill suggestion is shown as a dialog presentation. */
        public static final int UI_TYPE_DIALOG = 3;

        /**
         * The autofill suggestion is shown os a credman bottom sheet
         *
         * <p>Note, this was introduced as bottom sheet even though it applies to all credman UI
         * types. Instead of exposing this directly to the public, the generic UI_TYPE_CREDMAN is
         * introduced with the same number.
         *
         * @hide
         */
        public static final int UI_TYPE_CREDMAN_BOTTOM_SHEET = 4;

        /** Credential Manager suggestions are shown instead of Autofill suggestion */
        @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
        public static final int UI_TYPE_CREDMAN = 4;

        /** @hide */
        @IntDef(prefix = { "UI_TYPE_" }, value = {
                UI_TYPE_UNKNOWN,
                UI_TYPE_MENU,
                UI_TYPE_INLINE,
                UI_TYPE_DIALOG,
                UI_TYPE_CREDMAN_BOTTOM_SHEET
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface UiType {}

        @EventIds private final int mEventType;
        @Nullable private final String mDatasetId;
        @Nullable private final Bundle mClientState;

        // Note: mSelectedDatasetIds is stored as List<> instead of Set because Session already
        // stores it as List
        @Nullable private final List<String> mSelectedDatasetIds;
        @Nullable private final ArraySet<String> mIgnoredDatasetIds;

        @Nullable private final ArrayList<AutofillId> mChangedFieldIds;
        @Nullable private final ArrayList<String> mChangedDatasetIds;

        @Nullable private final ArrayList<AutofillId> mManuallyFilledFieldIds;
        @Nullable private final ArrayList<ArrayList<String>> mManuallyFilledDatasetIds;

        @Nullable private final AutofillId[] mDetectedFieldIds;
        @Nullable private final FieldClassification[] mDetectedFieldClassifications;

        @NoSaveReason private final int mSaveDialogNotShowReason;


        @UiType
        private final int mUiType;

        @Nullable private final AutofillId mFocusedId;

        /**
         * Returns the type of the event.
         *
         * @return The type of the event
         */
        public int getType() {
            return mEventType;
        }

        /** Gets the {@code AutofillId} that's focused at the time of action */
        @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
        @Nullable
        public AutofillId getFocusedId() {
            return mFocusedId;
        }

        /**
         * Returns the id of dataset the id was on.
         *
         * @return The id of dataset, or {@code null} the event is not associated with a dataset.
         */
        @Nullable public String getDatasetId() {
            return mDatasetId;
        }

        /**
         * Returns the client state from the {@link FillResponse} used to generate this event.
         *
         * <p><b>Note: </b>the state is associated with the app that was autofilled in the previous
         * {@link
         * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)},
         * which is not necessary the same app being autofilled now.
         */
        @Nullable public Bundle getClientState() {
            return mClientState;
        }

        /**
         * Returns which datasets were selected by the user.
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}.
         */
        @NonNull public Set<String> getSelectedDatasetIds() {
            return mSelectedDatasetIds == null ? Collections.emptySet()
                    : new ArraySet<>(mSelectedDatasetIds);
        }

        /**
         * Returns which datasets were shown to the user.
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_DATASETS_SHOWN}.
         */
        @FlaggedApi(FLAG_AUTOFILL_W_METRICS)
        @NonNull
        public Set<String> getShownDatasetIds() {
            return Collections.emptySet();
        }

        /**
         * Returns which datasets were NOT selected by the user.
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}.
         */
        @NonNull public Set<String> getIgnoredDatasetIds() {
            return mIgnoredDatasetIds == null ? Collections.emptySet() : mIgnoredDatasetIds;
        }

        /**
         * Returns which fields in the selected datasets were changed by the user after the dataset
         * was selected.
         *
         * <p>For example, server provides:
         *
         * <pre class="prettyprint">
         *  FillResponse response = new FillResponse.Builder()
         *      .addDataset(new Dataset.Builder(presentation1)
         *          .setId("4815")
         *          .setValue(usernameId, AutofillValue.forText("MrPlow"))
         *          .build())
         *      .addDataset(new Dataset.Builder(presentation2)
         *          .setId("162342")
         *          .setValue(passwordId, AutofillValue.forText("D'OH"))
         *          .build())
         *      .build();
         * </pre>
         *
         * <p>User select both datasets (for username and password) but after the fields are
         * autofilled, user changes them to:
         *
         * <pre class="prettyprint">
         *   username = "ElBarto";
         *   password = "AyCaramba";
         * </pre>
         *
         * <p>Then the result is the following map:
         *
         * <pre class="prettyprint">
         *   usernameId => "4815"
         *   passwordId => "162342"
         * </pre>
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}.
         *
         * @return map map whose key is the id of the change fields, and value is the id of
         * dataset that has that field and was selected by the user.
         */
        @NonNull public Map<AutofillId, String> getChangedFields() {
            if (mChangedFieldIds == null || mChangedDatasetIds == null) {
                return Collections.emptyMap();
            }

            final int size = mChangedFieldIds.size();
            final ArrayMap<AutofillId, String> changedFields = new ArrayMap<>(size);
            for (int i = 0; i < size; i++) {
                changedFields.put(mChangedFieldIds.get(i), mChangedDatasetIds.get(i));
            }
            return changedFields;
        }

        /**
         * Gets the <a href="AutofillService.html#FieldClassification">field classification</a>
         * results.
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}, when the
         * service requested {@link FillResponse.Builder#setFieldClassificationIds(AutofillId...)
         * field classification}.
         */
        @NonNull public Map<AutofillId, FieldClassification> getFieldsClassification() {
            if (mDetectedFieldIds == null) {
                return Collections.emptyMap();
            }
            final int size = mDetectedFieldIds.length;
            final ArrayMap<AutofillId, FieldClassification> map = new ArrayMap<>(size);
            for (int i = 0; i < size; i++) {
                final AutofillId id = mDetectedFieldIds[i];
                final FieldClassification fc = mDetectedFieldClassifications[i];
                if (sVerbose) {
                    Log.v(TAG, "getFieldsClassification[" + i + "]: id=" + id + ", fc=" + fc);
                }
                map.put(id, fc);
            }
            return map;
        }

        /**
         * Returns which fields were available on datasets provided by the service but manually
         * entered by the user.
         *
         * <p>For example, server provides:
         *
         * <pre class="prettyprint">
         *  FillResponse response = new FillResponse.Builder()
         *      .addDataset(new Dataset.Builder(presentation1)
         *          .setId("4815")
         *          .setValue(usernameId, AutofillValue.forText("MrPlow"))
         *          .setValue(passwordId, AutofillValue.forText("AyCaramba"))
         *          .build())
         *      .addDataset(new Dataset.Builder(presentation2)
         *          .setId("162342")
         *          .setValue(usernameId, AutofillValue.forText("ElBarto"))
         *          .setValue(passwordId, AutofillValue.forText("D'OH"))
         *          .build())
         *      .addDataset(new Dataset.Builder(presentation3)
         *          .setId("108")
         *          .setValue(usernameId, AutofillValue.forText("MrPlow"))
         *          .setValue(passwordId, AutofillValue.forText("D'OH"))
         *          .build())
         *      .build();
         * </pre>
         *
         * <p>User doesn't select a dataset but manually enters:
         *
         * <pre class="prettyprint">
         *   username = "MrPlow";
         *   password = "D'OH";
         * </pre>
         *
         * <p>Then the result is the following map:
         *
         * <pre class="prettyprint">
         *   usernameId => { "4815", "108"}
         *   passwordId => { "162342", "108" }
         * </pre>
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}.
         *
         * @return map map whose key is the id of the manually-entered field, and value is the
         * ids of the datasets that have that value but were not selected by the user.
         */
        @NonNull public Map<AutofillId, Set<String>> getManuallyEnteredField() {
            if (mManuallyFilledFieldIds == null || mManuallyFilledDatasetIds == null) {
                return Collections.emptyMap();
            }

            final int size = mManuallyFilledFieldIds.size();
            final Map<AutofillId, Set<String>> manuallyFilledFields = new ArrayMap<>(size);
            for (int i = 0; i < size; i++) {
                final AutofillId fieldId = mManuallyFilledFieldIds.get(i);
                final ArrayList<String> datasetIds = mManuallyFilledDatasetIds.get(i);
                manuallyFilledFields.put(fieldId, new ArraySet<>(datasetIds));
            }
            return manuallyFilledFields;
        }

        /**
         * Returns the reason why a save dialog was not shown.
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_CONTEXT_COMMITTED}. For the other
         * event types, the reason is set to NO_SAVE_UI_REASON_NONE.
         *
         * @return The reason why a save dialog was not shown.
         */
        @NoSaveReason
        public int getNoSaveUiReason() {
            return mSaveDialogNotShowReason;
        }

        /**
         * Returns fill suggestion ui presentation type which corresponds to types
         * defined in {@link android.service.autofill.Presentations).
         *
         * <p><b>Note: </b>Only set on events of type {@link #TYPE_DATASETS_SHOWN} and
         * {@link #TYPE_DATASET_SELECTED}. For the other event types, the type is set to
         * {@link #UI_TYPE_UNKNOWN }.
         *
         * @return The ui presentation type shown for user.
         */
        @UiType
        public int getUiType() {
            return mUiType;
        }

        /**
         * Creates a new event.
         *
         * @param eventType The type of the event
         * @param datasetId The dataset the event was on, or {@code null} if the event was on the
         *                  whole response.
         * @param clientState The client state associated with the event.
         * @param selectedDatasetIds The ids of datasets selected by the user.
         * @param ignoredDatasetIds The ids of datasets NOT select by the user.
         * @param changedFieldIds The ids of fields changed by the user.
         * @param changedDatasetIds The ids of the datasets that havd values matching the
         * respective entry on {@code changedFieldIds}.
         * @param manuallyFilledFieldIds The ids of fields that were manually entered by the user
         * and belonged to datasets.
         * @param manuallyFilledDatasetIds The ids of datasets that had values matching the
         * respective entry on {@code manuallyFilledFieldIds}.
         * @param detectedFieldClassifications the field classification matches.
         * @param focusedId the field which was focused at the time of event trigger
         *
         * @throws IllegalArgumentException If the length of {@code changedFieldIds} and
         * {@code changedDatasetIds} doesn't match.
         * @throws IllegalArgumentException If the length of {@code manuallyFilledFieldIds} and
         * {@code manuallyFilledDatasetIds} doesn't match.
         *
         * @hide
         */
        public Event(int eventType, @Nullable String datasetId, @Nullable Bundle clientState,
                @Nullable List<String> selectedDatasetIds,
                @Nullable ArraySet<String> ignoredDatasetIds,
                @Nullable ArrayList<AutofillId> changedFieldIds,
                @Nullable ArrayList<String> changedDatasetIds,
                @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
                @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds,
                @Nullable AutofillId[] detectedFieldIds,
                @Nullable FieldClassification[] detectedFieldClassifications,
                @Nullable AutofillId focusedId) {
            this(eventType, datasetId, clientState, selectedDatasetIds, ignoredDatasetIds,
                    changedFieldIds, changedDatasetIds, manuallyFilledFieldIds,
                    manuallyFilledDatasetIds, detectedFieldIds, detectedFieldClassifications,
                    NO_SAVE_UI_REASON_NONE, focusedId);
        }

        /**
         * Creates a new event.
         *
         * @param eventType The type of the event
         * @param datasetId The dataset the event was on, or {@code null} if the event was on the
         *                  whole response.
         * @param clientState The client state associated with the event.
         * @param selectedDatasetIds The ids of datasets selected by the user.
         * @param ignoredDatasetIds The ids of datasets NOT select by the user.
         * @param changedFieldIds The ids of fields changed by the user.
         * @param changedDatasetIds The ids of the datasets that havd values matching the
         * respective entry on {@code changedFieldIds}.
         * @param manuallyFilledFieldIds The ids of fields that were manually entered by the user
         * and belonged to datasets.
         * @param manuallyFilledDatasetIds The ids of datasets that had values matching the
         * respective entry on {@code manuallyFilledFieldIds}.
         * @param detectedFieldClassifications the field classification matches.
         * @param saveDialogNotShowReason The reason why a save dialog was not shown.
         * @param focusedId the field which was focused at the time of event trigger
         *
         * @throws IllegalArgumentException If the length of {@code changedFieldIds} and
         * {@code changedDatasetIds} doesn't match.
         * @throws IllegalArgumentException If the length of {@code manuallyFilledFieldIds} and
         * {@code manuallyFilledDatasetIds} doesn't match.
         *
         * @hide
         */
        public Event(int eventType, @Nullable String datasetId, @Nullable Bundle clientState,
                @Nullable List<String> selectedDatasetIds,
                @Nullable ArraySet<String> ignoredDatasetIds,
                @Nullable ArrayList<AutofillId> changedFieldIds,
                @Nullable ArrayList<String> changedDatasetIds,
                @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
                @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds,
                @Nullable AutofillId[] detectedFieldIds,
                @Nullable FieldClassification[] detectedFieldClassifications,
                int saveDialogNotShowReason,
                @Nullable AutofillId focusedId) {
            this(eventType, datasetId, clientState, selectedDatasetIds, ignoredDatasetIds,
                    changedFieldIds, changedDatasetIds, manuallyFilledFieldIds,
                    manuallyFilledDatasetIds, detectedFieldIds, detectedFieldClassifications,
                    saveDialogNotShowReason, UI_TYPE_UNKNOWN, focusedId);
        }

        /**
         * Creates a new event.
         *
         * @param eventType The type of the event
         * @param datasetId The dataset the event was on, or {@code null} if the event was on the
         *                  whole response.
         * @param clientState The client state associated with the event.
         * @param selectedDatasetIds The ids of datasets selected by the user.
         * @param ignoredDatasetIds The ids of datasets NOT select by the user.
         * @param changedFieldIds The ids of fields changed by the user.
         * @param changedDatasetIds The ids of the datasets that havd values matching the
         * respective entry on {@code changedFieldIds}.
         * @param manuallyFilledFieldIds The ids of fields that were manually entered by the user
         * and belonged to datasets.
         * @param manuallyFilledDatasetIds The ids of datasets that had values matching the
         * respective entry on {@code manuallyFilledFieldIds}.
         * @param detectedFieldClassifications the field classification matches.
         * @param saveDialogNotShowReason The reason why a save dialog was not shown.
         * @param uiType The ui presentation type for fill suggestion.
         * @param focusedId the field which was focused at the time of event trigger
         *
         * @throws IllegalArgumentException If the length of {@code changedFieldIds} and
         * {@code changedDatasetIds} doesn't match.
         * @throws IllegalArgumentException If the length of {@code manuallyFilledFieldIds} and
         * {@code manuallyFilledDatasetIds} doesn't match.
         *
         * @hide
         */
        public Event(int eventType, @Nullable String datasetId, @Nullable Bundle clientState,
                @Nullable List<String> selectedDatasetIds,
                @Nullable ArraySet<String> ignoredDatasetIds,
                @Nullable ArrayList<AutofillId> changedFieldIds,
                @Nullable ArrayList<String> changedDatasetIds,
                @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
                @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds,
                @Nullable AutofillId[] detectedFieldIds,
                @Nullable FieldClassification[] detectedFieldClassifications,
                int saveDialogNotShowReason, int uiType, @Nullable AutofillId focusedId) {
            mEventType = Preconditions.checkArgumentInRange(eventType, 0,
                    TYPE_VIEW_REQUESTED_AUTOFILL, "eventType");
            mDatasetId = datasetId;
            mClientState = clientState;
            mSelectedDatasetIds = selectedDatasetIds;
            mIgnoredDatasetIds = ignoredDatasetIds;
            if (changedFieldIds != null) {
                Preconditions.checkArgument(!ArrayUtils.isEmpty(changedFieldIds)
                                && changedDatasetIds != null
                                && changedFieldIds.size() == changedDatasetIds.size(),
                        "changed ids must have same length and not be empty");
            }
            mChangedFieldIds = changedFieldIds;
            mChangedDatasetIds = changedDatasetIds;
            if (manuallyFilledFieldIds != null) {
                Preconditions.checkArgument(!ArrayUtils.isEmpty(manuallyFilledFieldIds)
                                && manuallyFilledDatasetIds != null
                                && manuallyFilledFieldIds.size() == manuallyFilledDatasetIds.size(),
                        "manually filled ids must have same length and not be empty");
            }
            mManuallyFilledFieldIds = manuallyFilledFieldIds;
            mManuallyFilledDatasetIds = manuallyFilledDatasetIds;

            mDetectedFieldIds = detectedFieldIds;
            mDetectedFieldClassifications = detectedFieldClassifications;

            mSaveDialogNotShowReason = Preconditions.checkArgumentInRange(saveDialogNotShowReason,
                    NO_SAVE_UI_REASON_NONE, NO_SAVE_UI_REASON_DATASET_MATCH,
                    "saveDialogNotShowReason");
            mUiType = uiType;
            mFocusedId = focusedId;
        }

        @Override
        public String toString() {
            return "FillEvent [datasetId=" + mDatasetId
                    + ", type=" + eventToString(mEventType)
                    + ", uiType=" + uiTypeToString(mUiType)
                    + ", selectedDatasets=" + mSelectedDatasetIds
                    + ", ignoredDatasetIds=" + mIgnoredDatasetIds
                    + ", changedFieldIds=" + mChangedFieldIds
                    + ", changedDatasetsIds=" + mChangedDatasetIds
                    + ", manuallyFilledFieldIds=" + mManuallyFilledFieldIds
                    + ", manuallyFilledDatasetIds=" + mManuallyFilledDatasetIds
                    + ", detectedFieldIds=" + Arrays.toString(mDetectedFieldIds)
                    + ", detectedFieldClassifications ="
                        + Arrays.toString(mDetectedFieldClassifications)
                    + ", saveDialogNotShowReason=" + mSaveDialogNotShowReason
                    + "]";
        }

        private static String eventToString(int eventType) {
            switch (eventType) {
                case TYPE_DATASET_SELECTED:
                    return "TYPE_DATASET_SELECTED";
                case TYPE_DATASET_AUTHENTICATION_SELECTED:
                    return "TYPE_DATASET_AUTHENTICATION_SELECTED";
                case TYPE_AUTHENTICATION_SELECTED:
                    return "TYPE_AUTHENTICATION_SELECTED";
                case TYPE_SAVE_SHOWN:
                    return "TYPE_SAVE_SHOWN";
                case TYPE_CONTEXT_COMMITTED:
                    return "TYPE_CONTEXT_COMMITTED";
                case TYPE_DATASETS_SHOWN:
                    return "TYPE_DATASETS_SHOWN";
                case TYPE_VIEW_REQUESTED_AUTOFILL:
                    return "TYPE_VIEW_REQUESTED_AUTOFILL";
                default:
                    return "TYPE_UNKNOWN";
            }
        }

        private static String uiTypeToString(int uiType) {
            switch (uiType) {
                case UI_TYPE_MENU:
                    return "UI_TYPE_MENU";
                case UI_TYPE_INLINE:
                    return "UI_TYPE_INLINE";
                case UI_TYPE_DIALOG:
                    return "UI_TYPE_FILL_DIALOG";
                case UI_TYPE_CREDMAN_BOTTOM_SHEET:
                    return "UI_TYPE_CREDMAN_BOTTOM_SHEET";
                default:
                    return "UI_TYPE_UNKNOWN";
            }
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<FillEventHistory> CREATOR =
            new Parcelable.Creator<FillEventHistory>() {
                @Override
                public FillEventHistory createFromParcel(Parcel parcel) {
                    FillEventHistory selection = new FillEventHistory(0, parcel.readBundle());

                    final int numEvents = parcel.readInt();
                    for (int i = 0; i < numEvents; i++) {
                        final int eventType = parcel.readInt();
                        final String datasetId = parcel.readString();
                        final Bundle clientState = parcel.readBundle();
                        final ArrayList<String> selectedDatasetIds = parcel.createStringArrayList();
                        @SuppressWarnings("unchecked")
                        final ArraySet<String> ignoredDatasets =
                                (ArraySet<String>) parcel.readArraySet(null);
                        final ArrayList<AutofillId> changedFieldIds =
                                parcel.createTypedArrayList(AutofillId.CREATOR);
                        final ArrayList<String> changedDatasetIds = parcel.createStringArrayList();

                        final ArrayList<AutofillId> manuallyFilledFieldIds =
                                parcel.createTypedArrayList(AutofillId.CREATOR);
                        final ArrayList<ArrayList<String>> manuallyFilledDatasetIds;
                        if (manuallyFilledFieldIds != null) {
                            final int size = manuallyFilledFieldIds.size();
                            manuallyFilledDatasetIds = new ArrayList<>(size);
                            for (int j = 0; j < size; j++) {
                                manuallyFilledDatasetIds.add(parcel.createStringArrayList());
                            }
                        } else {
                            manuallyFilledDatasetIds = null;
                        }
                        final AutofillId[] detectedFieldIds = parcel.readParcelableArray(null,
                                AutofillId.class);
                        final FieldClassification[] detectedFieldClassifications =
                                (detectedFieldIds != null)
                                ? FieldClassification.readArrayFromParcel(parcel)
                                : null;
                        final int saveDialogNotShowReason = parcel.readInt();
                        final int uiType = parcel.readInt();
                        AutofillId focusedId = null;
                        if (Flags.addLastFocusedIdToFillEventHistory()) {
                            focusedId = parcel.readParcelable(null, AutofillId.class);
                        }

                        selection.addEvent(new Event(eventType, datasetId, clientState,
                                selectedDatasetIds, ignoredDatasets,
                                changedFieldIds, changedDatasetIds,
                                manuallyFilledFieldIds, manuallyFilledDatasetIds,
                                detectedFieldIds, detectedFieldClassifications,
                                saveDialogNotShowReason, uiType, focusedId));
                    }
                    return selection;
                }

                @Override
                public FillEventHistory[] newArray(int size) {
                    return new FillEventHistory[size];
                }
            };
}

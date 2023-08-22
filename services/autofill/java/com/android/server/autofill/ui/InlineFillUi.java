/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.IntentSender;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.InlinePresentation;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.autofill.RemoteInlineSuggestionRenderService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;


/**
 * UI for a particular field (i.e. {@link AutofillId}) based on an inline autofill response from
 * the autofill service or the augmented autofill service. It wraps multiple inline suggestions.
 *
 * <p> This class is responsible for filtering the suggestions based on the filtered text.
 * It'll create {@link InlineSuggestion} instances by reusing the backing remote views (from the
 * renderer service) if possible.
 */
public final class InlineFillUi {

    private static final String TAG = "InlineFillUi";

    /**
     * The id of the field which the current Ui is for.
     */
    @NonNull
    final AutofillId mAutofillId;

    /**
     * The list of inline suggestions, before applying any filtering
     */
    @NonNull
    private final ArrayList<InlineSuggestion> mInlineSuggestions;

    /**
     * The corresponding data sets for the inline suggestions. The list may be null if the current
     * Ui is the authentication UI for the response. If non-null, the size of data sets should equal
     * that of  inline suggestions.
     */
    @Nullable
    private final ArrayList<Dataset> mDatasets;

    /**
     * The filter text which will be applied on the inline suggestion list before they are returned
     * as a response.
     */
    @Nullable
    private String mFilterText;

    /**
     * Whether prefix/regex based filtering is disabled.
     */
    private boolean mFilterMatchingDisabled;

    /**
     * Returns an empty inline autofill UI.
     */
    @NonNull
    public static InlineFillUi emptyUi(@NonNull AutofillId autofillId) {
        return new InlineFillUi(autofillId);
    }

    /**
     * If user enters more characters than this length, the autofill suggestion won't be shown.
     */
    private int mMaxInputLengthForAutofill = Integer.MAX_VALUE;

    /**
     * Encapsulates various arguments used by {@link #forAutofill} and {@link #forAugmentedAutofill}
     */
    public static class InlineFillUiInfo {

        public int mUserId;
        public int mSessionId;
        public InlineSuggestionsRequest mInlineRequest;
        public AutofillId mFocusedId;
        public String mFilterText;
        public RemoteInlineSuggestionRenderService mRemoteRenderService;

        public InlineFillUiInfo(@NonNull InlineSuggestionsRequest inlineRequest,
                @NonNull AutofillId focusedId, @NonNull String filterText,
                @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
                @UserIdInt int userId, int sessionId) {
            mUserId = userId;
            mSessionId = sessionId;
            mInlineRequest = inlineRequest;
            mFocusedId = focusedId;
            mFilterText = filterText;
            mRemoteRenderService = remoteRenderService;
        }
    }

    /**
     * Returns an inline autofill UI for a field based on an Autofilll response.
     */
    @NonNull
    public static InlineFillUi forAutofill(@NonNull InlineFillUiInfo inlineFillUiInfo,
            @NonNull FillResponse response,
            @NonNull InlineSuggestionUiCallback uiCallback, int maxInputLengthForAutofill) {
        if (response.getAuthentication() != null && response.getInlinePresentation() != null) {
            InlineSuggestion inlineAuthentication =
                    InlineSuggestionFactory.createInlineAuthentication(inlineFillUiInfo, response,
                            uiCallback);
            return new InlineFillUi(inlineFillUiInfo, inlineAuthentication,
                    maxInputLengthForAutofill);
        } else if (response.getDatasets() != null) {
            SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions =
                    InlineSuggestionFactory.createInlineSuggestions(inlineFillUiInfo,
                            InlineSuggestionInfo.SOURCE_AUTOFILL, response.getDatasets(),
                            uiCallback);
            return new InlineFillUi(inlineFillUiInfo, inlineSuggestions,
                    maxInputLengthForAutofill);
        }
        return new InlineFillUi(inlineFillUiInfo, new SparseArray<>(), maxInputLengthForAutofill);
    }

    /**
     * Returns an inline autofill UI for a field based on an Autofilll response.
     */
    @NonNull
    public static InlineFillUi forAugmentedAutofill(@NonNull InlineFillUiInfo inlineFillUiInfo,
            @NonNull List<Dataset> datasets,
            @NonNull InlineSuggestionUiCallback uiCallback) {
        SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions =
                InlineSuggestionFactory.createInlineSuggestions(inlineFillUiInfo,
                        InlineSuggestionInfo.SOURCE_PLATFORM, datasets, uiCallback);
        return new InlineFillUi(inlineFillUiInfo, inlineSuggestions);
    }

    /**
     * Used by augmented autofill
     */
    private InlineFillUi(@Nullable InlineFillUiInfo inlineFillUiInfo,
            @NonNull SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions) {
        mAutofillId = inlineFillUiInfo.mFocusedId;
        int size = inlineSuggestions.size();
        mDatasets = new ArrayList<>(size);
        mInlineSuggestions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Pair<Dataset, InlineSuggestion> value = inlineSuggestions.valueAt(i);
            mDatasets.add(value.first);
            mInlineSuggestions.add(value.second);
        }
        mFilterText = inlineFillUiInfo.mFilterText;
    }

    /**
     * Used by normal autofill
     */
    private InlineFillUi(@Nullable InlineFillUiInfo inlineFillUiInfo,
            @NonNull SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions,
            int maxInputLengthForAutofill) {
        mAutofillId = inlineFillUiInfo.mFocusedId;
        int size = inlineSuggestions.size();
        mDatasets = new ArrayList<>(size);
        mInlineSuggestions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Pair<Dataset, InlineSuggestion> value = inlineSuggestions.valueAt(i);
            mDatasets.add(value.first);
            mInlineSuggestions.add(value.second);
        }
        mFilterText = inlineFillUiInfo.mFilterText;
        mMaxInputLengthForAutofill = maxInputLengthForAutofill;
    }

    /**
     * Used by normal autofill
     */
    private InlineFillUi(@NonNull InlineFillUiInfo inlineFillUiInfo,
            @NonNull InlineSuggestion inlineSuggestion, int maxInputLengthForAutofill) {
        mAutofillId = inlineFillUiInfo.mFocusedId;
        mDatasets = null;
        mInlineSuggestions = new ArrayList<>();
        mInlineSuggestions.add(inlineSuggestion);
        mFilterText = inlineFillUiInfo.mFilterText;
        mMaxInputLengthForAutofill = maxInputLengthForAutofill;
    }

    /**
     * Only used for constructing an empty InlineFillUi with {@link #emptyUi}
     */
    private InlineFillUi(@NonNull AutofillId focusedId) {
        mAutofillId = focusedId;
        mDatasets = new ArrayList<>(0);
        mInlineSuggestions = new ArrayList<>(0);
        mFilterText = null;
    }

    @NonNull
    public AutofillId getAutofillId() {
        return mAutofillId;
    }

    public void setFilterText(@Nullable String filterText) {
        mFilterText = filterText;
    }

    /**
     * Returns the list of filtered inline suggestions suitable for being sent to the IME.
     */
    @NonNull
    public InlineSuggestionsResponse getInlineSuggestionsResponse() {
        final int size = mInlineSuggestions.size();
        if (size == 0) {
            return new InlineSuggestionsResponse(Collections.emptyList());
        }
        final List<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        if (mDatasets == null || mDatasets.size() != size) {
            // authentication case
            for (int i = 0; i < size; i++) {
                inlineSuggestions.add(copy(i, mInlineSuggestions.get(i)));
            }
            return new InlineSuggestionsResponse(inlineSuggestions);
        }

        // Do not show inline suggestion if user entered more than a certain number of characters.
        if (!TextUtils.isEmpty(mFilterText) && mFilterText.length() > mMaxInputLengthForAutofill) {
            if (sVerbose) {
                Slog.v(TAG, "Not showing inline suggestion when user entered more than "
                         + mMaxInputLengthForAutofill + " characters");
            }
            return new InlineSuggestionsResponse(inlineSuggestions);
        }

        for (int i = 0; i < size; i++) {
            final Dataset dataset = mDatasets.get(i);
            final int fieldIndex = dataset.getFieldIds().indexOf(mAutofillId);
            if (fieldIndex < 0) {
                Slog.w(TAG, "AutofillId=" + mAutofillId + " not found in dataset");
                continue;
            }
            final InlinePresentation inlinePresentation = dataset.getFieldInlinePresentation(
                    fieldIndex);
            if (inlinePresentation == null) {
                Slog.w(TAG, "InlinePresentation not found in dataset");
                continue;
            }
            if (!inlinePresentation.isPinned()  // don't filter pinned suggestions
                    && !includeDataset(dataset, fieldIndex)) {
                continue;
            }
            inlineSuggestions.add(copy(i, mInlineSuggestions.get(i)));
        }
        return new InlineSuggestionsResponse(inlineSuggestions);
    }

    /**
     * Returns a copy of the suggestion, that internally copies the {@link IInlineContentProvider}
     * so that it's not reused by the remote IME process across different inline suggestions.
     * See {@link InlineContentProviderImpl} for why this is needed.
     *
     * <p>Note that although it copies the {@link IInlineContentProvider}, the underlying remote
     * view (in the renderer service) is still reused.
     */
    @NonNull
    private InlineSuggestion copy(int index, @NonNull InlineSuggestion inlineSuggestion) {
        final IInlineContentProvider contentProvider = inlineSuggestion.getContentProvider();
        if (contentProvider instanceof InlineContentProviderImpl) {
            // We have to create a new inline suggestion instance to ensure we don't reuse the
            // same {@link IInlineContentProvider}, but the underlying views are reused when
            // calling {@link InlineContentProviderImpl#copy()}.
            InlineSuggestion newInlineSuggestion = new InlineSuggestion(inlineSuggestion
                    .getInfo(), ((InlineContentProviderImpl) contentProvider).copy());
            // The remote view is only set when the content provider is called to inflate the view,
            // which happens after it's sent to the IME (i.e. not now), so we keep the latest
            // content provider (through newInlineSuggestion) to make sure the next time we copy it,
            // we get to reuse the view.
            mInlineSuggestions.set(index, newInlineSuggestion);
            return newInlineSuggestion;
        }
        return inlineSuggestion;
    }

    // TODO: Extract the shared filtering logic here and in FillUi to a common method.
    private boolean includeDataset(Dataset dataset, int fieldIndex) {
        // Show everything when the user input is empty.
        if (TextUtils.isEmpty(mFilterText)) {
            return true;
        }

        final String constraintLowerCase = mFilterText.toString().toLowerCase();

        // Use the filter provided by the service, if available.
        final Dataset.DatasetFieldFilter filter = dataset.getFilter(fieldIndex);
        if (filter != null) {
            Pattern filterPattern = filter.pattern;
            if (filterPattern == null) {
                if (sVerbose) {
                    Slog.v(TAG, "Explicitly disabling filter for dataset id" + dataset.getId());
                }
                return false;
            }
            if (mFilterMatchingDisabled) {
                return false;
            }
            return filterPattern.matcher(constraintLowerCase).matches();
        }

        final AutofillValue value = dataset.getFieldValues().get(fieldIndex);
        if (value == null || !value.isText()) {
            return dataset.getAuthentication() == null;
        }
        if (mFilterMatchingDisabled) {
            return false;
        }
        final String valueText = value.getTextValue().toString().toLowerCase();
        return valueText.toLowerCase().startsWith(constraintLowerCase);
    }

    /**
     * Disables prefix/regex based filtering. Other filtering rules (see {@link
     * android.service.autofill.Dataset}) still apply.
     */
    public void disableFilterMatching() {
        mFilterMatchingDisabled = true;
    }

    /**
     * Callback from the inline suggestion Ui.
     */
    public interface InlineSuggestionUiCallback {
        /**
         * Callback to autofill a dataset to the client app.
         */
        void autofill(@NonNull Dataset dataset, int datasetIndex);

        /**
         * Callback to authenticate a dataset.
         *
         * <p>Only implemented by regular autofill for now.</p>
         */
        void authenticate(int requestId, int datasetIndex);

        /**
         * Callback to start Intent in client app.
         */
        void startIntentSender(@NonNull IntentSender intentSender);

        /**
         * Callback on errors.
         */
        void onError();

        /**
         * Callback when the when the IME inflates the suggestion
         *
         * This goes through the following path:
         * 1. IME Chip inflation inflate() ->
         * 2. RemoteInlineSuggestionUi::handleInlineSuggestionUiReady() ->
         * 3. RemoteInlineSuggestionViewConnector::onRender() ->
         * 4. InlineSuggestionUiCallback::onInflate()
         */
        void onInflate();
    }

    /**
     * Callback for inline suggestion Ui related events.
     */
    public interface InlineUiEventCallback {
        /**
         * Callback to notify inline ui is shown.
         */
        void notifyInlineUiShown(@NonNull AutofillId autofillId);

        /**
         * Callback to notify inline ui is hidden.
         */
        void notifyInlineUiHidden(@NonNull AutofillId autofillId);
    }
}

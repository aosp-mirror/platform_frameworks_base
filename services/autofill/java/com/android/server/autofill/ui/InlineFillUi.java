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
import android.content.Intent;
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
        return new InlineFillUi(autofillId, new SparseArray<>(), null);
    }

    /**
     * Returns an inline autofill UI for a field based on an Autofilll response.
     */
    @NonNull
    public static InlineFillUi forAutofill(@NonNull InlineSuggestionsRequest request,
            @NonNull FillResponse response,
            @NonNull AutofillId focusedViewId, @Nullable String filterText,
            @NonNull AutoFillUI.AutoFillUiCallback uiCallback,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId) {

        if (InlineSuggestionFactory.responseNeedAuthentication(response)) {
            InlineSuggestion inlineAuthentication =
                    InlineSuggestionFactory.createInlineAuthentication(request, response,
                            uiCallback, onErrorCallback, remoteRenderService, userId, sessionId);
            return new InlineFillUi(focusedViewId, inlineAuthentication, filterText);
        } else if (response.getDatasets() != null) {
            SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions =
                    InlineSuggestionFactory.createAutofillInlineSuggestions(request,
                            response.getRequestId(),
                            response.getDatasets(), focusedViewId, uiCallback, onErrorCallback,
                            remoteRenderService, userId, sessionId);
            return new InlineFillUi(focusedViewId, inlineSuggestions, filterText);
        }
        return new InlineFillUi(focusedViewId, new SparseArray<>(), filterText);
    }

    /**
     * Returns an inline autofill UI for a field based on an Autofilll response.
     */
    @NonNull
    public static InlineFillUi forAugmentedAutofill(@NonNull InlineSuggestionsRequest request,
            @NonNull List<Dataset> datasets,
            @NonNull AutofillId focusedViewId, @Nullable String filterText,
            @NonNull InlineSuggestionUiCallback uiCallback,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId) {
        SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions =
                InlineSuggestionFactory.createAugmentedAutofillInlineSuggestions(request, datasets,
                        focusedViewId,
                        uiCallback, onErrorCallback, remoteRenderService, userId, sessionId);
        return new InlineFillUi(focusedViewId, inlineSuggestions, filterText);
    }

    InlineFillUi(@NonNull AutofillId autofillId,
            @NonNull SparseArray<Pair<Dataset, InlineSuggestion>> inlineSuggestions,
            @Nullable String filterText) {
        mAutofillId = autofillId;
        int size = inlineSuggestions.size();
        mDatasets = new ArrayList<>(size);
        mInlineSuggestions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Pair<Dataset, InlineSuggestion> value = inlineSuggestions.valueAt(i);
            mDatasets.add(value.first);
            mInlineSuggestions.add(value.second);
        }
        mFilterText = filterText;
    }

    InlineFillUi(@NonNull AutofillId autofillId, InlineSuggestion inlineSuggestion,
            @Nullable String filterText) {
        mAutofillId = autofillId;
        mDatasets = null;
        mInlineSuggestions = new ArrayList<>();
        mInlineSuggestions.add(inlineSuggestion);
        mFilterText = filterText;
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
         * Callback to start Intent in client app.
         */
        void startIntentSender(@NonNull IntentSender intentSender, @NonNull Intent intent);
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

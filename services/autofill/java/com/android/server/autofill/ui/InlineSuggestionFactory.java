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

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.InlinePresentation;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillManager;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.inline.InlinePresentationSpec;

import com.android.internal.view.inline.IInlineContentProvider;

import java.util.List;

final class InlineSuggestionFactory {
    private static final String TAG = "InlineSuggestionFactory";

    public static InlineSuggestion createInlineAuthentication(
            @NonNull InlineFillUi.InlineFillUiInfo inlineFillUiInfo, @NonNull FillResponse response,
            @NonNull InlineFillUi.InlineSuggestionUiCallback uiCallback) {
        InlinePresentation inlineAuthentication = response.getInlinePresentation();
        final int requestId = response.getRequestId();

        return createInlineSuggestion(inlineFillUiInfo, InlineSuggestionInfo.SOURCE_AUTOFILL,
                InlineSuggestionInfo.TYPE_ACTION, () -> uiCallback.authenticate(requestId,
                        AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED),
                mergedInlinePresentation(inlineFillUiInfo.mInlineRequest, 0, inlineAuthentication),
                uiCallback);
    }

    /**
     * Creates an array of {@link InlineSuggestion}s with the {@code datasets} provided by either
     * regular/augmented autofill services.
     */
    @Nullable
    public static SparseArray<Pair<Dataset, InlineSuggestion>> createInlineSuggestions(
            @NonNull InlineFillUi.InlineFillUiInfo inlineFillUiInfo,
            @NonNull @InlineSuggestionInfo.Source String suggestionSource,
            @NonNull List<Dataset> datasets,
            @NonNull InlineFillUi.InlineSuggestionUiCallback uiCallback) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestions(source=" + suggestionSource + ") called");

        final InlineSuggestionsRequest request = inlineFillUiInfo.mInlineRequest;
        SparseArray<Pair<Dataset, InlineSuggestion>> response = new SparseArray<>(datasets.size());
        for (int datasetIndex = 0; datasetIndex < datasets.size(); datasetIndex++) {
            final Dataset dataset = datasets.get(datasetIndex);
            final int fieldIndex = dataset.getFieldIds().indexOf(inlineFillUiInfo.mFocusedId);
            if (fieldIndex < 0) {
                Slog.w(TAG, "AutofillId=" + inlineFillUiInfo.mFocusedId + " not found in dataset");
                continue;
            }

            final InlinePresentation inlinePresentation =
                    dataset.getFieldInlinePresentation(fieldIndex);
            if (inlinePresentation == null) {
                Slog.w(TAG, "InlinePresentation not found in dataset");
                continue;
            }

            final String suggestionType =
                    dataset.getAuthentication() == null ? InlineSuggestionInfo.TYPE_SUGGESTION
                            : InlineSuggestionInfo.TYPE_ACTION;
            final int index = datasetIndex;

            InlineSuggestion inlineSuggestion = createInlineSuggestion(
                    inlineFillUiInfo, suggestionSource, suggestionType,
                    () -> uiCallback.autofill(dataset, index),
                    mergedInlinePresentation(request, datasetIndex, inlinePresentation),
                    uiCallback);
            response.append(datasetIndex, Pair.create(dataset, inlineSuggestion));
        }

        return response;
    }

    private static InlineSuggestion createInlineSuggestion(
            @NonNull InlineFillUi.InlineFillUiInfo inlineFillUiInfo,
            @NonNull @InlineSuggestionInfo.Source String suggestionSource,
            @NonNull @InlineSuggestionInfo.Type String suggestionType,
            @NonNull Runnable onClickAction,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineFillUi.InlineSuggestionUiCallback uiCallback) {
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(), suggestionSource,
                inlinePresentation.getAutofillHints(), suggestionType,
                inlinePresentation.isPinned());

        return new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlineFillUiInfo, inlinePresentation,
                        onClickAction, uiCallback));
    }

    /**
     * Returns an {@link InlinePresentation} with the style spec from the request/host, and
     * everything else from the provided {@code inlinePresentation}.
     */
    private static InlinePresentation mergedInlinePresentation(
            @NonNull InlineSuggestionsRequest request,
            int index, @NonNull InlinePresentation inlinePresentation) {
        final List<InlinePresentationSpec> specs = request.getInlinePresentationSpecs();
        if (specs.isEmpty()) {
            return inlinePresentation;
        }
        InlinePresentationSpec specFromHost = specs.get(Math.min(specs.size() - 1, index));
        InlinePresentationSpec mergedInlinePresentation = new InlinePresentationSpec.Builder(
                inlinePresentation.getInlinePresentationSpec().getMinSize(),
                inlinePresentation.getInlinePresentationSpec().getMaxSize()).setStyle(
                specFromHost.getStyle()).build();

        return new InlinePresentation(inlinePresentation.getSlice(), mergedInlinePresentation,
                inlinePresentation.isPinned());
    }

    private static IInlineContentProvider createInlineContentProvider(
            @NonNull InlineFillUi.InlineFillUiInfo inlineFillUiInfo,
            @NonNull InlinePresentation inlinePresentation, @Nullable Runnable onClickAction,
            @NonNull InlineFillUi.InlineSuggestionUiCallback uiCallback) {
        RemoteInlineSuggestionViewConnector remoteInlineSuggestionViewConnector =
                new RemoteInlineSuggestionViewConnector(inlineFillUiInfo, inlinePresentation,
                        onClickAction, uiCallback);

        return new InlineContentProviderImpl(remoteInlineSuggestionViewConnector, null);
    }

    private InlineSuggestionFactory() {
    }
}
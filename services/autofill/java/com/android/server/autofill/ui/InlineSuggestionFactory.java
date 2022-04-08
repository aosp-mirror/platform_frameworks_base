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
import android.content.Intent;
import android.content.IntentSender;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.InlinePresentation;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.inline.InlinePresentationSpec;

import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.autofill.RemoteInlineSuggestionRenderService;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class InlineSuggestionFactory {
    private static final String TAG = "InlineSuggestionFactory";

    public static boolean responseNeedAuthentication(@NonNull FillResponse response) {
        return response.getAuthentication() != null && response.getInlinePresentation() != null;
    }

    public static InlineSuggestion createInlineAuthentication(
            @NonNull InlineSuggestionsRequest request, @NonNull FillResponse response,
            @NonNull AutoFillUI.AutoFillUiCallback client, @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService, int userId,
            int sessionId) {
        final BiConsumer<Dataset, Integer> onClickFactory = (dataset, datasetIndex) -> {
            client.authenticate(response.getRequestId(),
                    datasetIndex, response.getAuthentication(), response.getClientState(),
                    /* authenticateInline= */ true);
        };
        final Consumer<IntentSender> intentSenderConsumer = (intentSender) ->
                client.startIntentSender(intentSender, new Intent());
        InlinePresentation inlineAuthentication = response.getInlinePresentation();
        return createInlineAuthSuggestion(
                mergedInlinePresentation(request, 0, inlineAuthentication),
                remoteRenderService, userId, sessionId,
                onClickFactory, onErrorCallback, intentSenderConsumer,
                request.getHostInputToken(), request.getHostDisplayId());
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by the
     * autofill service, potentially filtering the datasets.
     */
    @Nullable
    public static SparseArray<Pair<Dataset, InlineSuggestion>> createAutofillInlineSuggestions(
            @NonNull InlineSuggestionsRequest request, int requestId,
            @NonNull List<Dataset> datasets,
            @NonNull AutofillId autofillId,
            @NonNull AutoFillUI.AutoFillUiCallback client, @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestionsResponse called");
        final Consumer<IntentSender> intentSenderConsumer = (intentSender) ->
                client.startIntentSender(intentSender, new Intent());
        final BiConsumer<Dataset, Integer> onClickFactory = (dataset, datasetIndex) -> {
            client.fill(requestId, datasetIndex, dataset);
        };

        return createInlineSuggestionsInternal(/* isAugmented= */ false, request,
                datasets, autofillId,
                onErrorCallback, onClickFactory, intentSenderConsumer, remoteRenderService, userId,
                sessionId);
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by augmented
     * autofill service.
     */
    @Nullable
    public static SparseArray<Pair<Dataset, InlineSuggestion>>
            createAugmentedAutofillInlineSuggestions(
            @NonNull InlineSuggestionsRequest request, @NonNull List<Dataset> datasets,
            @NonNull AutofillId autofillId,
            @NonNull InlineFillUi.InlineSuggestionUiCallback inlineSuggestionUiCallback,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId) {
        if (sDebug) Slog.d(TAG, "createAugmentedInlineSuggestionsResponse called");
        return createInlineSuggestionsInternal(/* isAugmented= */ true, request,
                datasets, autofillId, onErrorCallback,
                (dataset, datasetIndex) ->
                        inlineSuggestionUiCallback.autofill(dataset, datasetIndex),
                (intentSender) ->
                        inlineSuggestionUiCallback.startIntentSender(intentSender, new Intent()),
                remoteRenderService, userId, sessionId);
    }

    @Nullable
    private static SparseArray<Pair<Dataset, InlineSuggestion>> createInlineSuggestionsInternal(
            boolean isAugmented, @NonNull InlineSuggestionsRequest request,
            @NonNull List<Dataset> datasets, @NonNull AutofillId autofillId,
            @NonNull Runnable onErrorCallback, @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @NonNull Consumer<IntentSender> intentSenderConsumer,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId) {
        SparseArray<Pair<Dataset, InlineSuggestion>> response = new SparseArray<>(datasets.size());
        for (int datasetIndex = 0; datasetIndex < datasets.size(); datasetIndex++) {
            final Dataset dataset = datasets.get(datasetIndex);
            final int fieldIndex = dataset.getFieldIds().indexOf(autofillId);
            if (fieldIndex < 0) {
                Slog.w(TAG, "AutofillId=" + autofillId + " not found in dataset");
                continue;
            }
            final InlinePresentation inlinePresentation = dataset.getFieldInlinePresentation(
                    fieldIndex);
            if (inlinePresentation == null) {
                Slog.w(TAG, "InlinePresentation not found in dataset");
                continue;
            }
            InlineSuggestion inlineSuggestion = createInlineSuggestion(isAugmented, dataset,
                    datasetIndex,
                    mergedInlinePresentation(request, datasetIndex, inlinePresentation),
                    onClickFactory, remoteRenderService, userId, sessionId,
                    onErrorCallback, intentSenderConsumer,
                    request.getHostInputToken(), request.getHostDisplayId());
            response.append(datasetIndex, Pair.create(dataset, inlineSuggestion));
        }
        return response;
    }

    private static InlineSuggestion createInlineSuggestion(boolean isAugmented,
            @NonNull Dataset dataset, int datasetIndex,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId,
            @NonNull Runnable onErrorCallback, @NonNull Consumer<IntentSender> intentSenderConsumer,
            @Nullable IBinder hostInputToken,
            int displayId) {
        final String suggestionSource = isAugmented ? InlineSuggestionInfo.SOURCE_PLATFORM
                : InlineSuggestionInfo.SOURCE_AUTOFILL;
        final String suggestionType =
                dataset.getAuthentication() == null ? InlineSuggestionInfo.TYPE_SUGGESTION
                        : InlineSuggestionInfo.TYPE_ACTION;
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(), suggestionSource,
                inlinePresentation.getAutofillHints(), suggestionType,
                inlinePresentation.isPinned());

        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation,
                        () -> onClickFactory.accept(dataset, datasetIndex), onErrorCallback,
                        intentSenderConsumer, remoteRenderService, userId, sessionId,
                        hostInputToken, displayId));

        return inlineSuggestion;
    }

    private static InlineSuggestion createInlineAuthSuggestion(
            @NonNull InlinePresentation inlinePresentation,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId,
            @NonNull BiConsumer<Dataset, Integer> onClickFactory, @NonNull Runnable onErrorCallback,
            @NonNull Consumer<IntentSender> intentSenderConsumer,
            @Nullable IBinder hostInputToken, int displayId) {
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                InlineSuggestionInfo.SOURCE_AUTOFILL, inlinePresentation.getAutofillHints(),
                InlineSuggestionInfo.TYPE_ACTION, inlinePresentation.isPinned());

        return new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation,
                        () -> onClickFactory.accept(null,
                                AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED),
                        onErrorCallback, intentSenderConsumer, remoteRenderService, userId,
                        sessionId, hostInputToken, displayId));
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
            @NonNull InlinePresentation inlinePresentation, @Nullable Runnable onClickAction,
            @NonNull Runnable onErrorCallback,
            @NonNull Consumer<IntentSender> intentSenderConsumer,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            int userId, int sessionId,
            @Nullable IBinder hostInputToken,
            int displayId) {
        RemoteInlineSuggestionViewConnector
                remoteInlineSuggestionViewConnector = new RemoteInlineSuggestionViewConnector(
                remoteRenderService, userId, sessionId, inlinePresentation, hostInputToken,
                displayId, onClickAction, onErrorCallback, intentSenderConsumer);
        InlineContentProviderImpl inlineContentProvider = new InlineContentProviderImpl(
                remoteInlineSuggestionViewConnector, null);
        return inlineContentProvider;
    }

    private InlineSuggestionFactory() {
    }
}
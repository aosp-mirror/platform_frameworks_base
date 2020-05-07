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
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentSender;
import android.os.IBinder;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.InlinePresentation;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.inline.InlinePresentationSpec;

import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.autofill.RemoteInlineSuggestionRenderService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class InlineSuggestionFactory {
    private static final String TAG = "InlineSuggestionFactory";

    /**
     * Callback from the inline suggestion Ui.
     */
    public interface InlineSuggestionUiCallback {
        /**
         * Callback to autofill a dataset to the client app.
         */
        void autofill(@NonNull Dataset dataset);

        /**
         * Callback to start Intent in client app.
         */
        void startIntentSender(@NonNull IntentSender intentSender, @NonNull Intent intent);
    }

    /**
     * Returns a copy of the response, that internally copies the {@link IInlineContentProvider}
     * so that it's not reused by the remote IME process across different inline suggestions.
     * See {@link InlineContentProviderImpl} for why this is needed.
     */
    @NonNull
    public static InlineSuggestionsResponse copy(@NonNull InlineSuggestionsResponse response) {
        final ArrayList<InlineSuggestion> copiedInlineSuggestions = new ArrayList<>();
        for (InlineSuggestion inlineSuggestion : response.getInlineSuggestions()) {
            final IInlineContentProvider contentProvider = inlineSuggestion.getContentProvider();
            if (contentProvider instanceof InlineContentProviderImpl) {
                copiedInlineSuggestions.add(new
                        InlineSuggestion(inlineSuggestion.getInfo(),
                        ((InlineContentProviderImpl) contentProvider).copy()));
            } else {
                copiedInlineSuggestions.add(inlineSuggestion);
            }
        }
        return new InlineSuggestionsResponse(copiedInlineSuggestions);
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by the
     * autofill service, potentially filtering the datasets.
     */
    @Nullable
    public static InlineSuggestionsResponse createInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request, @NonNull FillResponse response,
            @Nullable String filterText, @NonNull AutofillId autofillId,
            @NonNull AutoFillUI.AutoFillUiCallback client, @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestionsResponse called");
        final BiConsumer<Dataset, Integer> onClickFactory;
        if (response.getAuthentication() != null) {
            onClickFactory = (dataset, datasetIndex) -> {
                client.requestHideFillUi(autofillId);
                client.authenticate(response.getRequestId(),
                        datasetIndex, response.getAuthentication(), response.getClientState(),
                        /* authenticateInline= */ true);
            };
        } else {
            onClickFactory = (dataset, datasetIndex) -> {
                client.requestHideFillUi(autofillId);
                client.fill(response.getRequestId(), datasetIndex, dataset);
            };
        }

        final InlinePresentation inlineAuthentication =
                response.getAuthentication() == null ? null : response.getInlinePresentation();
        return createInlineSuggestionsResponseInternal(/* isAugmented= */ false, request,
                response.getDatasets(), filterText, inlineAuthentication, autofillId,
                onErrorCallback, onClickFactory, (intentSender) ->
                        client.startIntentSender(intentSender, new Intent()), remoteRenderService);
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by augmented
     * autofill service.
     */
    @Nullable
    public static InlineSuggestionsResponse createAugmentedInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request, @NonNull List<Dataset> datasets,
            @NonNull AutofillId autofillId,
            @NonNull InlineSuggestionUiCallback inlineSuggestionUiCallback,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService) {
        if (sDebug) Slog.d(TAG, "createAugmentedInlineSuggestionsResponse called");
        return createInlineSuggestionsResponseInternal(/* isAugmented= */ true, request,
                datasets, /* filterText= */ null, /* inlineAuthentication= */ null,
                autofillId, onErrorCallback,
                (dataset, datasetIndex) ->
                        inlineSuggestionUiCallback.autofill(dataset),
                (intentSender) ->
                        inlineSuggestionUiCallback.startIntentSender(intentSender, new Intent()),
                remoteRenderService);
    }

    @Nullable
    private static InlineSuggestionsResponse createInlineSuggestionsResponseInternal(
            boolean isAugmented, @NonNull InlineSuggestionsRequest request,
            @Nullable List<Dataset> datasets, @Nullable String filterText,
            @Nullable InlinePresentation inlineAuthentication, @NonNull AutofillId autofillId,
            @NonNull Runnable onErrorCallback, @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @NonNull Consumer<IntentSender> intentSenderConsumer,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService) {

        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        if (inlineAuthentication != null) {
            InlineSuggestion inlineAuthSuggestion = createInlineAuthSuggestion(inlineAuthentication,
                    remoteRenderService, onClickFactory, onErrorCallback, intentSenderConsumer,
                    request.getHostInputToken(), request.getHostDisplayId());
            inlineSuggestions.add(inlineAuthSuggestion);

            return new InlineSuggestionsResponse(inlineSuggestions);
        }

        if (datasets == null) {
            Slog.w(TAG, "Datasets should not be null here");
            return null;
        }

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
            if (!inlinePresentation.isPinned()  // don't filter pinned suggestions
                    && !includeDataset(dataset, fieldIndex, filterText)) {
                continue;
            }
            InlineSuggestion inlineSuggestion = createInlineSuggestion(isAugmented, dataset,
                    datasetIndex,
                    mergedInlinePresentation(request, datasetIndex, inlinePresentation),
                    onClickFactory, remoteRenderService, onErrorCallback, intentSenderConsumer,
                    request.getHostInputToken(), request.getHostDisplayId());

            inlineSuggestions.add(inlineSuggestion);
        }
        return new InlineSuggestionsResponse(inlineSuggestions);
    }

    // TODO: Extract the shared filtering logic here and in FillUi to a common method.
    private static boolean includeDataset(Dataset dataset, int fieldIndex,
            @Nullable String filterText) {
        // Show everything when the user input is empty.
        if (TextUtils.isEmpty(filterText)) {
            return true;
        }

        final String constraintLowerCase = filterText.toString().toLowerCase();

        // Use the filter provided by the service, if available.
        final Dataset.DatasetFieldFilter filter = dataset.getFilter(fieldIndex);
        if (filter != null) {
            Pattern filterPattern = filter.pattern;
            if (filterPattern == null) {
                if (sVerbose) {
                    Slog.v(TAG, "Explicitly disabling filter for dataset id" + dataset.getId());
                }
                return true;
            }
            return filterPattern.matcher(constraintLowerCase).matches();
        }

        final AutofillValue value = dataset.getFieldValues().get(fieldIndex);
        if (value == null || !value.isText()) {
            return dataset.getAuthentication() == null;
        }
        final String valueText = value.getTextValue().toString().toLowerCase();
        return valueText.toLowerCase().startsWith(constraintLowerCase);
    }

    private static InlineSuggestion createInlineSuggestion(boolean isAugmented,
            @NonNull Dataset dataset, int datasetIndex,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
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
                        intentSenderConsumer, remoteRenderService, hostInputToken, displayId));

        return inlineSuggestion;
    }

    private static InlineSuggestion createInlineAuthSuggestion(
            @NonNull InlinePresentation inlinePresentation,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
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
                        onErrorCallback, intentSenderConsumer, remoteRenderService, hostInputToken,
                        displayId));
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
            @Nullable IBinder hostInputToken,
            int displayId) {
        RemoteInlineSuggestionViewConnector
                remoteInlineSuggestionViewConnector = new RemoteInlineSuggestionViewConnector(
                remoteRenderService, inlinePresentation, hostInputToken, displayId, onClickAction,
                onErrorCallback, intentSenderConsumer);
        InlineContentProviderImpl inlineContentProvider = new InlineContentProviderImpl(
                remoteInlineSuggestionViewConnector, null);
        return inlineContentProvider;
    }

    private InlineSuggestionFactory() {
    }
}
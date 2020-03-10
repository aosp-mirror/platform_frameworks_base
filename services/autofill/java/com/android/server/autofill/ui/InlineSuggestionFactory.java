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
import android.content.IntentSender;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.IInlineSuggestionUiCallback;
import android.service.autofill.InlineAction;
import android.service.autofill.InlinePresentation;
import android.text.TextUtils;
import android.util.Slog;
import android.view.SurfaceControlViewHost;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inline.InlinePresentationSpec;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.LocalServices;
import com.android.server.UiThread;
import com.android.server.autofill.RemoteInlineSuggestionRenderService;
import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
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
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by the
     * autofill service, potentially filtering the datasets.
     */
    @Nullable
    public static InlineSuggestionsResponse createInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request, @NonNull FillResponse response,
            @Nullable String filterText, @Nullable List<InlineAction> inlineActions,
            @NonNull AutofillId autofillId,
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
                response.getDatasets(), filterText, inlineAuthentication, inlineActions, autofillId,
                onErrorCallback, onClickFactory, remoteRenderService);
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by augmented
     * autofill service.
     */
    @Nullable
    public static InlineSuggestionsResponse createAugmentedInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request, @NonNull List<Dataset> datasets,
            @Nullable List<InlineAction> inlineActions, @NonNull AutofillId autofillId,
            @NonNull InlineSuggestionUiCallback inlineSuggestionUiCallback,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService) {
        if (sDebug) Slog.d(TAG, "createAugmentedInlineSuggestionsResponse called");
        return createInlineSuggestionsResponseInternal(/* isAugmented= */ true, request,
                datasets, /* filterText= */ null, /* inlineAuthentication= */ null,
                inlineActions, autofillId, onErrorCallback,
                (dataset, datasetIndex) ->
                        inlineSuggestionUiCallback.autofill(dataset), remoteRenderService);
    }

    @Nullable
    private static InlineSuggestionsResponse createInlineSuggestionsResponseInternal(
            boolean isAugmented, @NonNull InlineSuggestionsRequest request,
            @Nullable List<Dataset> datasets, @Nullable String filterText,
            @Nullable InlinePresentation inlineAuthentication,
            @Nullable List<InlineAction> inlineActions, @NonNull AutofillId autofillId,
            @NonNull Runnable onErrorCallback, @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService) {

        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        if (inlineAuthentication != null) {
            InlineSuggestion inlineAuthSuggestion = createInlineAuthSuggestion(inlineAuthentication,
                    remoteRenderService, onClickFactory, onErrorCallback,
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
            if (!includeDataset(dataset, fieldIndex, filterText)) {
                continue;
            }
            InlineSuggestion inlineSuggestion = createInlineSuggestion(isAugmented, dataset,
                    datasetIndex,
                    mergedInlinePresentation(request, datasetIndex, inlinePresentation),
                    onClickFactory, remoteRenderService, onErrorCallback,
                    request.getHostInputToken(), request.getHostDisplayId());

            inlineSuggestions.add(inlineSuggestion);
        }
        if (inlineActions != null) {
            for (InlineAction inlineAction : inlineActions) {
                final InlineSuggestion inlineActionSuggestion = createInlineAction(isAugmented,
                        mergedInlinePresentation(request, 0, inlineAction.getInlinePresentation()),
                        inlineAction.getAction(),
                        remoteRenderService, onErrorCallback, request.getHostInputToken(),
                        request.getHostDisplayId());
                inlineSuggestions.add(inlineActionSuggestion);
            }
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


    private static InlineSuggestion createInlineAction(boolean isAugmented,
            @NonNull InlinePresentation presentation,
            @NonNull IntentSender action,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            @NonNull Runnable onErrorCallback, @Nullable IBinder hostInputToken,
            int displayId) {
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                presentation.getInlinePresentationSpec(),
                isAugmented ? InlineSuggestionInfo.SOURCE_PLATFORM
                        : InlineSuggestionInfo.SOURCE_AUTOFILL,
                presentation.getAutofillHints(), InlineSuggestionInfo.TYPE_ACTION,
                presentation.isPinned());
        final Runnable onClickAction = () -> {
            try {
                // TODO(b/150499490): route the intent to the client app to have it fired there,
                //  so that it will appear as a part of the same task as the client app (similar
                //  to the authentication flow).
                action.sendIntent(null, 0, null, null, null);
            } catch (IntentSender.SendIntentException e) {
                onErrorCallback.run();
                Slog.w(TAG, "Error sending inline action intent");
            }
        };
        return new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(presentation, onClickAction, onErrorCallback,
                        remoteRenderService, hostInputToken, displayId));
    }

    private static InlineSuggestion createInlineSuggestion(boolean isAugmented,
            @NonNull Dataset dataset, int datasetIndex,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull BiConsumer<Dataset, Integer> onClickFactory,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
            @NonNull Runnable onErrorCallback, @Nullable IBinder hostInputToken,
            int displayId) {
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                isAugmented ? InlineSuggestionInfo.SOURCE_PLATFORM
                        : InlineSuggestionInfo.SOURCE_AUTOFILL,
                inlinePresentation.getAutofillHints(),
                InlineSuggestionInfo.TYPE_SUGGESTION, inlinePresentation.isPinned());

        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation,
                        () -> onClickFactory.accept(dataset, datasetIndex), onErrorCallback,
                        remoteRenderService, hostInputToken, displayId));

        return inlineSuggestion;
    }

    private static InlineSuggestion createInlineAuthSuggestion(
            @NonNull InlinePresentation inlinePresentation,
            @NonNull RemoteInlineSuggestionRenderService remoteRenderService,
            @NonNull BiConsumer<Dataset, Integer> onClickFactory, @NonNull Runnable onErrorCallback,
            @Nullable IBinder hostInputToken, int displayId) {
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                InlineSuggestionInfo.SOURCE_AUTOFILL, inlinePresentation.getAutofillHints(),
                InlineSuggestionInfo.TYPE_SUGGESTION, inlinePresentation.isPinned());

        return new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation,
                        () -> onClickFactory.accept(null,
                                AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED),
                        onErrorCallback, remoteRenderService, hostInputToken, displayId));
    }

    /**
     * Returns an {@link InlinePresentation} with the style spec from the request/host, and
     * everything else from the provided {@code inlinePresentation}.
     */
    private static InlinePresentation mergedInlinePresentation(
            @NonNull InlineSuggestionsRequest request,
            int index, @NonNull InlinePresentation inlinePresentation) {
        final List<InlinePresentationSpec> specs = request.getPresentationSpecs();
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

    private static IInlineContentProvider.Stub createInlineContentProvider(
            @NonNull InlinePresentation inlinePresentation, @Nullable Runnable onClickAction,
            @NonNull Runnable onErrorCallback,
            @Nullable RemoteInlineSuggestionRenderService remoteRenderService,
            @Nullable IBinder hostInputToken,
            int displayId) {
        return new IInlineContentProvider.Stub() {
            @Override
            public void provideContent(int width, int height, IInlineContentCallback callback) {
                UiThread.getHandler().post(() -> {
                    final IInlineSuggestionUiCallback uiCallback = createInlineSuggestionUiCallback(
                            callback, onClickAction, onErrorCallback);

                    if (remoteRenderService == null) {
                        Slog.e(TAG, "RemoteInlineSuggestionRenderService is null");
                        return;
                    }

                    remoteRenderService.renderSuggestion(uiCallback, inlinePresentation,
                            width, height, hostInputToken, displayId);
                });
            }
        };
    }

    private static IInlineSuggestionUiCallback.Stub createInlineSuggestionUiCallback(
            @NonNull IInlineContentCallback callback, @NonNull Runnable onAutofillCallback,
            @NonNull Runnable onErrorCallback) {
        return new IInlineSuggestionUiCallback.Stub() {
            @Override
            public void onAutofill() throws RemoteException {
                onAutofillCallback.run();
            }

            @Override
            public void onContent(SurfaceControlViewHost.SurfacePackage surface)
                    throws RemoteException {
                callback.onContent(surface);
            }

            @Override
            public void onError() throws RemoteException {
                onErrorCallback.run();
            }

            @Override
            public void onTransferTouchFocusToImeWindow(IBinder sourceInputToken, int displayId)
                    throws RemoteException {
                //TODO(b/149574510): Move logic to IMMS
                final WindowManagerInternal windowManagerInternal = LocalServices.getService(
                        WindowManagerInternal.class);
                if (!windowManagerInternal.transferTouchFocusToImeWindow(sourceInputToken,
                        displayId)) {
                    Slog.e(TAG, "Cannot transfer touch focus from suggestion to IME");
                    onErrorCallback.run();
                }
            }
        };
    }

    private InlineSuggestionFactory() {
    }
}
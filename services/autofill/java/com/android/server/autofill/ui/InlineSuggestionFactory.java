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
import android.content.Context;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.InlinePresentation;
import android.text.TextUtils;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.inline.InlinePresentationSpec;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.Toast;

import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.UiThread;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
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
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by
     * augmented autofill service.
     */
    public static InlineSuggestionsResponse createAugmentedInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request,
            @NonNull Dataset[] datasets,
            @NonNull AutofillId autofillId,
            @NonNull Context context,
            @NonNull InlineSuggestionUiCallback inlineSuggestionUiCallback,
            @NonNull Runnable onErrorCallback) {
        if (sDebug) Slog.d(TAG, "createAugmentedInlineSuggestionsResponse called");
        return createInlineSuggestionsResponseInternal(/* isAugmented= */ true, request,
                datasets, /* filterText= */ null, /* inlineActions= */ null, autofillId, context,
                onErrorCallback,
                (dataset, filedIndex) -> (v -> inlineSuggestionUiCallback.autofill(dataset)));
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by the
     * autofill service, potentially filtering the datasets.
     */
    public static InlineSuggestionsResponse createInlineSuggestionsResponse(
            @NonNull InlineSuggestionsRequest request, int requestId,
            @NonNull Dataset[] datasets,
            @Nullable String filterText,
            @Nullable List<InlinePresentation> inlineActions,
            @NonNull AutofillId autofillId,
            @NonNull Context context,
            @NonNull AutoFillUI.AutoFillUiCallback client,
            @NonNull Runnable onErrorCallback) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestionsResponse called");
        return createInlineSuggestionsResponseInternal(/* isAugmented= */ false, request, datasets,
                filterText, inlineActions, autofillId, context, onErrorCallback,
                (dataset, filedIndex) -> (v -> client.fill(requestId, filedIndex, dataset)));
    }

    private static InlineSuggestionsResponse createInlineSuggestionsResponseInternal(
            boolean isAugmented, @NonNull InlineSuggestionsRequest request,
            @NonNull Dataset[] datasets, @Nullable String filterText,
            @Nullable List<InlinePresentation> inlineActions, @NonNull AutofillId autofillId,
            @NonNull Context context, @NonNull Runnable onErrorCallback,
            @NonNull BiFunction<Dataset, Integer, View.OnClickListener> onClickListenerFactory) {
        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        final InlineSuggestionUi inlineSuggestionUi = new InlineSuggestionUi(context,
                onErrorCallback);
        for (int i = 0; i < datasets.length; i++) {
            final Dataset dataset = datasets[i];
            final int fieldIndex = dataset.getFieldIds().indexOf(autofillId);
            if (fieldIndex < 0) {
                Slog.w(TAG, "AutofillId=" + autofillId + " not found in dataset");
                return null;
            }
            final InlinePresentation inlinePresentation = dataset.getFieldInlinePresentation(
                    fieldIndex);
            if (inlinePresentation == null) {
                Slog.w(TAG, "InlinePresentation not found in dataset");
                return null;
            }
            if (!includeDataset(dataset, fieldIndex, filterText)) {
                continue;
            }
            InlineSuggestion inlineSuggestion = createInlineSuggestion(isAugmented, dataset,
                    fieldIndex, mergedInlinePresentation(request, i, inlinePresentation),
                    inlineSuggestionUi, onClickListenerFactory);
            inlineSuggestions.add(inlineSuggestion);
        }
        if (inlineActions != null) {
            for (InlinePresentation inlinePresentation : inlineActions) {
                final InlineSuggestion inlineAction = createInlineAction(isAugmented, context,
                        mergedInlinePresentation(request, 0, inlinePresentation),
                        inlineSuggestionUi);
                inlineSuggestions.add(inlineAction);
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
            @NonNull Context context,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineSuggestionUi inlineSuggestionUi) {
        // TODO(b/146453195): fill in the autofill hint properly.
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                isAugmented ? InlineSuggestionInfo.SOURCE_PLATFORM
                        : InlineSuggestionInfo.SOURCE_AUTOFILL, new String[]{""},
                InlineSuggestionInfo.TYPE_ACTION);
        final View.OnClickListener onClickListener = v -> {
            // TODO(b/148567875): Launch the intent provided through the slice. This
            //  should be part of the UI renderer therefore will be moved to the support
            //  library.
            Toast.makeText(context, "icon clicked", Toast.LENGTH_SHORT).show();
        };
        return new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation, inlineSuggestionUi,
                        onClickListener));
    }

    private static InlineSuggestion createInlineSuggestion(boolean isAugmented,
            @NonNull Dataset dataset,
            int fieldIndex, @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @NonNull BiFunction<Dataset, Integer, View.OnClickListener> onClickListenerFactory) {
        // TODO(b/146453195): fill in the autofill hint properly.
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                isAugmented ? InlineSuggestionInfo.SOURCE_PLATFORM
                        : InlineSuggestionInfo.SOURCE_AUTOFILL, new String[]{""},
                InlineSuggestionInfo.TYPE_SUGGESTION);
        final View.OnClickListener onClickListener = onClickListenerFactory.apply(dataset,
                fieldIndex);
        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation, inlineSuggestionUi,
                        onClickListener));
        return inlineSuggestion;
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
            @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @Nullable View.OnClickListener onClickListener) {
        return new IInlineContentProvider.Stub() {
            @Override
            public void provideContent(int width, int height,
                    IInlineContentCallback callback) {
                UiThread.getHandler().post(() -> {
                    SurfaceControl sc = inlineSuggestionUi.inflate(inlinePresentation, width,
                            height,
                            onClickListener);
                    try {
                        callback.onContent(sc);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Encounter exception calling back with inline content.");
                    }
                });
            }
        };
    }

    private InlineSuggestionFactory() {
    }
}

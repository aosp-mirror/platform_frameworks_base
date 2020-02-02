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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.InlinePresentation;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.UiThread;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.autofill.ui.InlineSuggestionUi;

import java.util.ArrayList;


/**
 * @hide
 */
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
            @NonNull Dataset[] datasets,
            @NonNull AutofillId autofillId,
            @NonNull Context context,
            @NonNull InlineSuggestionUiCallback inlineSuggestionUiCallback) {
        if (sDebug) Slog.d(TAG, "createAugmentedInlineSuggestionsResponse called");

        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        final InlineSuggestionUi inlineSuggestionUi = new InlineSuggestionUi(context);
        for (Dataset dataset : datasets) {
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
            InlineSuggestion inlineSuggestion = createAugmentedInlineSuggestion(dataset,
                    inlinePresentation, inlineSuggestionUi, inlineSuggestionUiCallback);
            inlineSuggestions.add(inlineSuggestion);
        }
        return new InlineSuggestionsResponse(inlineSuggestions);
    }

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by the
     * autofill service.
     */
    public static InlineSuggestionsResponse createInlineSuggestionsResponse(int requestId,
            @NonNull Dataset[] datasets,
            @NonNull AutofillId autofillId,
            @NonNull Context context,
            @NonNull AutoFillUI.AutoFillUiCallback client) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestionsResponse called");

        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        final InlineSuggestionUi inlineSuggestionUi = new InlineSuggestionUi(context);
        for (Dataset dataset : datasets) {
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
            InlineSuggestion inlineSuggestion = createInlineSuggestion(requestId, dataset,
                    fieldIndex,
                    inlinePresentation, inlineSuggestionUi, client);
            inlineSuggestions.add(inlineSuggestion);
        }
        return new InlineSuggestionsResponse(inlineSuggestions);
    }

    private static InlineSuggestion createAugmentedInlineSuggestion(@NonNull Dataset dataset,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @NonNull InlineSuggestionUiCallback inlineSuggestionUiCallback) {
        // TODO(b/146453195): fill in the autofill hint properly.
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                InlineSuggestionInfo.SOURCE_PLATFORM, new String[]{""},
                InlineSuggestionInfo.TYPE_SUGGESTION);
        final View.OnClickListener onClickListener = v -> {
            inlineSuggestionUiCallback.autofill(dataset);
        };
        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation, inlineSuggestionUi,
                        onClickListener));
        return inlineSuggestion;
    }

    private static InlineSuggestion createInlineSuggestion(int requestId,
            @NonNull Dataset dataset,
            int fieldIndex,
            @NonNull InlinePresentation inlinePresentation,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @NonNull AutoFillUI.AutoFillUiCallback client) {
        // TODO(b/146453195): fill in the autofill hint properly.
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                inlinePresentation.getInlinePresentationSpec(),
                InlineSuggestionInfo.SOURCE_AUTOFILL, new String[]{""},
                InlineSuggestionInfo.TYPE_SUGGESTION);
        final View.OnClickListener onClickListener = v -> {
            client.fill(requestId, fieldIndex, dataset);
        };
        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(inlinePresentation, inlineSuggestionUi,
                        onClickListener));
        return inlineSuggestion;
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

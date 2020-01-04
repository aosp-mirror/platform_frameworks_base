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
import android.os.Handler;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.IAutoFillManagerClient;
import android.view.inline.InlinePresentationSpec;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.view.inline.IInlineContentCallback;
import com.android.internal.view.inline.IInlineContentProvider;
import com.android.server.autofill.ui.InlineSuggestionUi;

import java.util.ArrayList;


/**
 * @hide
 */
public final class InlineSuggestionFactory {
    private static final String TAG = "InlineSuggestionFactory";

    /**
     * Creates an {@link InlineSuggestionsResponse} with the {@code datasets} provided by
     * augmented autofill service.
     */
    public static InlineSuggestionsResponse createAugmentedInlineSuggestionsResponse(
            int sessionId,
            @NonNull Dataset[] datasets,
            @NonNull AutofillId autofillId,
            @NonNull InlineSuggestionsRequest request,
            @NonNull Handler uiHandler,
            @NonNull Context context,
            @NonNull IAutoFillManagerClient client) {
        if (sDebug) Slog.d(TAG, "createInlineSuggestionsResponse called");

        final ArrayList<InlineSuggestion> inlineSuggestions = new ArrayList<>();
        final InlineSuggestionUi inlineSuggestionUi = new InlineSuggestionUi(context);
        for (Dataset dataset : datasets) {
            // TODO(b/146453195): use the spec in the dataset.
            InlinePresentationSpec spec = request.getPresentationSpecs().get(0);
            if (spec == null) {
                Slog.w(TAG, "InlinePresentationSpec is not provided in the response data set");
                continue;
            }
            InlineSuggestion inlineSuggestion = createAugmentedInlineSuggestion(sessionId, dataset,
                    autofillId, spec, uiHandler, inlineSuggestionUi, client);
            inlineSuggestions.add(inlineSuggestion);
        }
        return new InlineSuggestionsResponse(inlineSuggestions);
    }

    private static InlineSuggestion createAugmentedInlineSuggestion(int sessionId,
            @NonNull Dataset dataset,
            @NonNull AutofillId autofillId,
            @NonNull InlinePresentationSpec spec,
            @NonNull Handler uiHandler,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @NonNull IAutoFillManagerClient client) {
        // TODO(b/146453195): fill in the autofill hint properly.
        final InlineSuggestionInfo inlineSuggestionInfo = new InlineSuggestionInfo(
                spec, InlineSuggestionInfo.SOURCE_PLATFORM, new String[]{""});
        final View.OnClickListener onClickListener = createOnClickListener(sessionId, dataset,
                client);
        final InlineSuggestion inlineSuggestion = new InlineSuggestion(inlineSuggestionInfo,
                createInlineContentProvider(autofillId, dataset, uiHandler, inlineSuggestionUi,
                        onClickListener));
        return inlineSuggestion;
    }

    private static IInlineContentProvider.Stub createInlineContentProvider(
            @NonNull AutofillId autofillId, @NonNull Dataset dataset, @NonNull Handler uiHandler,
            @NonNull InlineSuggestionUi inlineSuggestionUi,
            @Nullable View.OnClickListener onClickListener) {
        return new IInlineContentProvider.Stub() {
            @Override
            public void provideContent(int width, int height,
                    IInlineContentCallback callback) {
                uiHandler.post(() -> {
                    SurfaceControl sc = inlineSuggestionUi.inflate(dataset, autofillId,
                            width, height, onClickListener);
                    try {
                        callback.onContent(sc);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Encounter exception calling back with inline content.");
                    }
                });
            }
        };
    }

    private static View.OnClickListener createOnClickListener(int sessionId,
            @NonNull Dataset dataset,
            @NonNull IAutoFillManagerClient client) {
        return v -> {
            if (sDebug) Slog.d(TAG, "Inline suggestion clicked");
            try {
                client.autofill(sessionId, dataset.getFieldIds(), dataset.getFieldValues());
            } catch (RemoteException e) {
                Slog.w(TAG, "Encounter exception autofilling the values");
            }
        };
    }

    private InlineSuggestionFactory() {
    }
}

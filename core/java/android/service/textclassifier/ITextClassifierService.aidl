/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.service.textclassifier;

import android.service.textclassifier.IConversationActionsCallback;
import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextLanguageCallback;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextSelection;

/**
 * TextClassifierService binder interface.
 * See TextClassifier for interface documentation.
 * {@hide}
 */
oneway interface ITextClassifierService {

    void onSuggestSelection(
            in TextClassificationSessionId sessionId,
            in TextSelection.Request request,
            in ITextSelectionCallback callback);

    void onClassifyText(
            in TextClassificationSessionId sessionId,
            in TextClassification.Request request,
            in ITextClassificationCallback callback);

    void onGenerateLinks(
            in TextClassificationSessionId sessionId,
            in TextLinks.Request request,
            in ITextLinksCallback callback);

    // TODO: Remove
    void onSelectionEvent(
            in TextClassificationSessionId sessionId,
            in SelectionEvent event);

    void onTextClassifierEvent(
            in TextClassificationSessionId sessionId,
            in TextClassifierEvent event);

    void onCreateTextClassificationSession(
            in TextClassificationContext context,
            in TextClassificationSessionId sessionId);

    void onDestroyTextClassificationSession(
            in TextClassificationSessionId sessionId);

    void onDetectLanguage(
            in TextClassificationSessionId sessionId,
            in TextLanguage.Request request,
            in ITextLanguageCallback callback);

    void onSuggestConversationActions(
            in TextClassificationSessionId sessionId,
            in ConversationActions.Request request,
            in IConversationActionsCallback callback);
}

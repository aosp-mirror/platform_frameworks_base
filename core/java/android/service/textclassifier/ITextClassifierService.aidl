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

import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

/**
 * TextClassifierService binder interface.
 * See TextClassifier (and TextClassifier.Logger) for interface documentation.
 * {@hide}
 */
oneway interface ITextClassifierService {

    void onSuggestSelection(
            in CharSequence text, int selectionStartIndex, int selectionEndIndex,
            in TextSelection.Options options,
            in ITextSelectionCallback c);

    void onClassifyText(
            in CharSequence text, int startIndex, int endIndex,
            in TextClassification.Options options,
            in ITextClassificationCallback c);

    void onGenerateLinks(
            in CharSequence text,
            in TextLinks.Options options,
            in ITextLinksCallback c);

    void onSelectionEvent(in SelectionEvent event);
}

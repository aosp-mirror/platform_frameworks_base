/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.NonNull;
import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Interface to the text classification service.
 *
 * <p>You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 */
public final class TextClassificationManager {

    /** @hide */
    public TextClassificationManager(Context context) {}

    /**
     * Returns the default text classifier.
     */
    public TextClassifier getDefaultTextClassifier() {
        return TextClassifier.NO_OP;
    }

    /**
     * Returns information containing languages that were detected in the provided text.
     * This is a blocking operation you should avoid calling it on the UI thread.
     *
     * @throws IllegalArgumentException if text is null
     */
    public List<TextLanguage> detectLanguages(@NonNull CharSequence text) {
        // TODO: Implement
        return Collections.emptyList();
    }
}

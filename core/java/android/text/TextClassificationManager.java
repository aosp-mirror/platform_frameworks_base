/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text;

import android.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/**
 * Interface to the text classification service.
 * This class uses machine learning techniques to infer things about text.
 * Unless otherwise stated, methods of this class are blocking operations and should most likely not
 * be called on the UI thread.
 *
 * <p> You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 *
 * The TextClassificationManager serves as the default TextAssistant if none has been set.
 * @see android.app.Activity#setTextAssistant(TextAssistant).
 */
public final class TextClassificationManager implements TextAssistant {
    // TODO: Consider not making this class implement TextAssistant.

    /** @hide */
    public TextClassificationManager() {}

    /**
     * Returns information containing languages that were detected in the provided text.
     * This is a blocking operation and should most likely not be called on the UI thread.
     */
    public List<TextLanguage> detectLanguages(@NonNull CharSequence text) {
        // TODO: Implement this using the cld3 library.
        return Collections.emptyList();
    }

    @Override
    public TextSelection suggestSelection(
            @NonNull CharSequence text, int selectionStartIndex, int selectionEndIndex) {
        // TODO: Implement.
        return TextAssistant.NO_OP.suggestSelection(text, selectionStartIndex, selectionEndIndex);
    }

    @Override
    public void addLinks(@NonNull Spannable text, int linkMask) {
        // TODO: Implement.
    }
}

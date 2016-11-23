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

import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specifies detected languages for a section of text indicated by a start and end index.
 */
public final class TextLanguage {

    private final int mStartIndex;
    private final int mEndIndex;
    private final Map<String, Float> mLanguageConfidence;

    /**
     * Initializes a TextLanguage object.
     *
     * @param startIndex the start index of the detected languages in the text provided to generate
     *      this object.
     * @param endIndex the end index of the detected languages in the text provided to generate this
     *      object.
     * @param languageConfidence a map of detected language to confidence score. The language string
     *      is a BCP-47 language tag.
     * @throws NullPointerException if languageConfidence is null or contains a null key or value.
     */
    public TextLanguage(int startIndex, int endIndex,
            @NonNull Map<String, Float> languageConfidence) {
        mStartIndex = startIndex;
        mEndIndex = endIndex;

        Map<String, Float> map = new LinkedHashMap<>();
        Preconditions.checkNotNull(languageConfidence).entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> map.put(
                        Preconditions.checkNotNull(entry.getKey()),
                        Preconditions.checkNotNull(entry.getValue())));
        mLanguageConfidence = Collections.unmodifiableMap(map);
    }

    /**
     * Returns the start index of the detected languages in the text provided to generate this
     * object.
     */
    public int getStartIndex() {
        return mStartIndex;
    }

    /**
     * Returns the end index of the detected languages in the text provided to generate this object.
     */
    public int getEndIndex() {
        return mEndIndex;
    }

    /**
     * Returns an unmodifiable map of detected language to confidence score. The map entries are
     * ordered from high confidence score (1) to low confidence score (0). The language string is a
     * BCP-47 language tag.
     */
    @NonNull
    public Map<String, Float> getLanguageConfidence() {
        return mLanguageConfidence;
    }
}

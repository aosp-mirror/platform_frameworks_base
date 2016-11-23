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

import java.util.Collections;
import java.util.Map;

/**
 * Information about entities that a specific piece of text is classified as.
 */
public class TextClassification {

    /** @hide */
    public static final TextClassification NO_OP = new TextClassification();

    private Map<String, Float> mTypeConfidence = Collections.unmodifiableMap(Collections.EMPTY_MAP);

    /**
     * Returns a map of text classification types to their respective confidence scores.
     * The scores range from 0 (low confidence) to 1 (high confidence). The items are ordered from
     * high scoring items to low scoring items.
     */
    public Map<String, Float> getTypeConfidence() {
        return mTypeConfidence;
    }
}

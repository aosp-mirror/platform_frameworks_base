/*
 * Copyright (C) 2006 The Android Open Source Project
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


/**
 * This is the class for text whose content and markup are immutable.
 * For mutable markup, see {@link SpannableString}; for mutable text,
 * see {@link SpannableStringBuilder}.
 */
public final class SpannedString
extends SpannableStringInternal
implements CharSequence, GetChars, Spanned
{
    /**
     * @param source source object to copy from
     * @param ignoreNoCopySpan whether to copy NoCopySpans in the {@code source}
     * @hide
     */
    public SpannedString(CharSequence source, boolean ignoreNoCopySpan) {
        super(source, 0, source.length(), ignoreNoCopySpan);
    }

    /**
     * For the backward compatibility reasons, this constructor copies all spans including {@link
     * android.text.NoCopySpan}.
     * @param source source text
     */
    public SpannedString(CharSequence source) {
        this(source, false /* ignoreNoCopySpan */);  // preserve existing NoCopySpan behavior
    }

    private SpannedString(CharSequence source, int start, int end) {
        // preserve existing NoCopySpan behavior
        super(source, start, end, false /* ignoreNoCopySpan */);
    }

    public CharSequence subSequence(int start, int end) {
        return new SpannedString(this, start, end);
    }

    public static SpannedString valueOf(CharSequence source) {
        if (source instanceof SpannedString) {
            return (SpannedString) source;
        } else {
            return new SpannedString(source);
        }
    }
}

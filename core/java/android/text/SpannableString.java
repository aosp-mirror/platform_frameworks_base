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
 * This is the class for text whose content is immutable but to which
 * markup objects can be attached and detached.
 * For mutable text, see {@link SpannableStringBuilder}.
 */
public class SpannableString
extends SpannableStringInternal
implements CharSequence, GetChars, Spannable
{
    /**
     * @param source source object to copy from
     * @param ignoreNoCopySpan whether to copy NoCopySpans in the {@code source}
     * @hide
     */
    public SpannableString(CharSequence source, boolean ignoreNoCopySpan) {
        super(source, 0, source.length(), ignoreNoCopySpan);
    }

    /**
     * For the backward compatibility reasons, this constructor copies all spans including {@link
     * android.text.NoCopySpan}.
     * @param source source text
     */
    public SpannableString(CharSequence source) {
        this(source, false /* ignoreNoCopySpan */);  // preserve existing NoCopySpan behavior
    }

    private SpannableString(CharSequence source, int start, int end) {
        // preserve existing NoCopySpan behavior
        super(source, start, end, false /* ignoreNoCopySpan */);
    }

    public static SpannableString valueOf(CharSequence source) {
        if (source instanceof SpannableString) {
            return (SpannableString) source;
        } else {
            return new SpannableString(source);
        }
    }

    public void setSpan(Object what, int start, int end, int flags) {
        super.setSpan(what, start, end, flags);
    }

    public void removeSpan(Object what) {
        super.removeSpan(what);
    }

    public final CharSequence subSequence(int start, int end) {
        return new SpannableString(this, start, end);
    }
}

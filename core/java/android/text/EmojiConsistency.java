/*
 * Copyright (C) 2022 The Android Open Source Project
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
import java.util.Set;


/**
 * The set of emoji that should be drawn by the system with the default font for device consistency.
 *
 * This is intended to be used only by applications that do custom emoji rendering using tools like
 * {@link android.text.style.ReplacementSpan} or custom emoji fonts.
 *
 * An example of how this should be used:
 *
 * <p>
 *     <ol>
 *         <li>
 *             Match emoji for third party custom rendering
 *         </li>
 *         <li>
 *             For each match, check against NonStandardEmoji before displaying custom glyph
 *         </li>
 *         <li>
 *             If in NonStandardEmojiSet, do not display custom glyph (render with
 *             android.graphics.Typeface.DEFAULT instead)
 *         </li>
 *         <li>
 *             Otherwise, do custom rendering like normal
 *         </li>
 *     </ol>
 * </p>
 */
public final class EmojiConsistency {
    /* Cannot construct */
    private EmojiConsistency() { }

    /**
     * The set of emoji that should be drawn by the system with the default font for device
     * consistency.
     *
     * Apps SHOULD attempt to avoid overwriting system emoji rendering with custom emoji glyphs for
     * these codepoint sequences.
     *
     * Apps that display custom emoji glyphs via matching code may filter against this set. On
     * match, the application SHOULD prefer Typeface.Default instead of a custom glyph
     *
     * Apps that use fonts may use this set to add {@link android.text.style.TypefaceSpan} for
     * android.graphics.Typeface.DEFAULT for matched codepoint sequences.
     *
     * Codepoint sequences returned MUST match exactly to be considered a match with the exception
     * of Variation Selectors.
     *
     * All codepoint sequences returned MUST be a complete emoji codepoint sequence as defined by
     * unicode.
     *
     * @return set of codepoint sequences representing codepoints that should be rendered by the
     * system using the default font.
     */
    @NonNull
    public static Set<int[]> getEmojiConsistencySet() {
        return Collections.emptySet();
    }

}

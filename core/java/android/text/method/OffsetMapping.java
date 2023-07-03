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

package android.text.method;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The interface for the index mapping information of a transformed text returned by
 * {@link TransformationMethod}. This class is mainly used to support the
 * {@link TransformationMethod} that alters the text length.
 * @hide
 */
public interface OffsetMapping {
    /**
     * The mapping strategy for a character offset.
     *
     * @see #originalToTransformed(int, int)
     * @see #transformedToOriginal(int, int)
     */
    int MAP_STRATEGY_CHARACTER = 0;

    /**
     * The mapping strategy for a cursor position.
     *
     * @see #originalToTransformed(int, int)
     * @see #transformedToOriginal(int, int)
     */
    int MAP_STRATEGY_CURSOR = 1;

    @IntDef(prefix = { "MAP_STRATEGY" }, value = {
            MAP_STRATEGY_CHARACTER,
            MAP_STRATEGY_CURSOR
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MapStrategy {}

    /**
     * Map an offset at original text to the offset at transformed text. <br/>
     *
     * This function must be a monotonically non-decreasing function. In other words, if the offset
     * advances at the original text, the offset at the transformed text must advance or stay there.
     * <br/>
     *
     * Depending on the mapping strategy, a same offset can be mapped differently. For example,
     * <pre>
     * Original:       ABCDE
     * Transformed:    ABCXDE ('X' is introduced due to the transformation.)
     * </pre>
     * Let's check the offset 3, which is the offset of the character 'D'.
     * If we want to map the character offset 3, it should be mapped to index 4.
     * If we want to map the cursor offset 3 (the offset of the character before which the cursor is
     * placed), it's unclear if the mapped cursor is before 'X' or after 'X'.
     * This depends on how the transformed text reacts an insertion at offset 3 in the original
     * text. Assume character 'V' is insert at offset 3 in the original text, and the original text
     * become "ABCVDE". The transformed text can be:
     * <pre>
     * 1) "ABCVXDE"
     * or
     * 2) "ABCXVDE"
     * </pre>
     * In the first case, the offset 3 should be mapped to 3 (before 'X'). And in the second case,
     * the offset should be mapped to 4 (after 'X').<br/>
     *
     * In some cases, a character offset at the original text doesn't have a proper corresponding
     * offset at the transformed text. For example:
     * <pre>
     * Original:    ABCDE
     * Transformed: ABDE ('C' is deleted due to the transformation.)
     * </pre>
     * The character offset 2 can be mapped either to offset 2 or 3, but neither is meaningful. For
     * convenience, it MUST map to the next offset (offset 3 in this case), or the
     * transformedText.length() if there is no valid character to map.
     * This is mandatory when the map strategy is {@link #MAP_STRATEGY_CHARACTER}, but doesn't
     * apply for other map strategies.
     *
     * @param offset the offset at the original text. It's normally equal to or less than the
     *               originalText.length(). When {@link #MAP_STRATEGY_CHARACTER} is passed, it must
     *               be less than originalText.length(). For convenience, it's also allowed to be
     *               negative, which represents an invalid offset. When the given offset is
     *               negative, this method should return it as it is.
     * @param strategy the mapping strategy. Depending on its value, the same offset can be mapped
     *                 differently.
     * @return the mapped offset at the transformed text, must be equal to or less than the
     * transformedText.length().
     *
     * @see #transformedToOriginal(int, int)
     */
    int originalToTransformed(int offset, @MapStrategy int strategy);

    /**
     * Map an offset at transformed text to the offset at original text. This is the reverse method
     * of {@link #originalToTransformed(int, int)}. <br/>
     * This function must be a monotonically non-decreasing function. In other words, if the offset
     * advances at the original text, the offset at the transformed text must advance or stay there.
     * <br/>
     * Similar to the {@link #originalToTransformed(int, int)} if a character offset doesn't have a
     * corresponding offset at the transformed text, it MUST return the value as the previous
     * offset. This is mandatory when the map strategy is {@link #MAP_STRATEGY_CHARACTER},
     * but doesn't apply for other map strategies.
     *
     * @param offset the offset at the transformed text. It's normally equal to or less than the
     *               transformedText.length(). When {@link #MAP_STRATEGY_CHARACTER} is passed, it
     *               must be less than transformedText.length(). For convenience, it's also allowed
     *               to be negative, which represents an invalid offset. When the given offset is
     *               negative, this method should return it as it is.
     * @param strategy the mapping strategy. Depending on its value, the same offset can be mapped
     *                 differently.
     * @return the mapped offset at the original text, must be equal to or less than the
     * original.length().
     *
     * @see #originalToTransformed(int, int)
     */
    int transformedToOriginal(int offset, @MapStrategy int strategy);

    /**
     * Map a text update in the original text to an update the transformed text.
     * This method used to determine how the transformed text is updated in response to an
     * update in the original text. It is always called before the original text being changed.
     *
     * The main usage of this method is to update text layout incrementally. So it should report
     * the range where text needs to be laid out again.
     *
     * @param textUpdate the {@link TextUpdate} object containing the text  update information on
     *                  the original text. The transformed text update information will also be
     *                   stored at this object.
     */
    void originalToTransformed(TextUpdate textUpdate);

    /**
     * The class that stores the text update information that from index <code>where</code>,
     * <code>after</code> characters will replace the old text that has length <code>before</code>.
     */
    class TextUpdate {
        /** The start index of the text update range, inclusive */
        public int where;
        /** The length of the replaced old text. */
        public int before;
        /** The length of the new text that replaces the old text. */
        public int after;

        /**
         * Creates a {@link TextUpdate} object.
         * @param where the start index of the text update range.
         * @param before the length of the replaced old text.
         * @param after the length of the new text that replaces the old text.
         */
        public TextUpdate(int where, int before, int after) {
            this.where = where;
            this.before = before;
            this.after = after;
        }
    }
}

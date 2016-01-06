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
 * This is the interface for text whose content and markup
 * can be changed (as opposed
 * to immutable text like Strings).  If you make a {@link DynamicLayout}
 * of an Editable, the layout will be reflowed as the text is changed.
 */
public interface Editable
extends CharSequence, GetChars, Spannable, Appendable
{
    /**
     * Replaces the specified range (<code>st&hellip;en</code>) of text in this
     * Editable with a copy of the slice <code>start&hellip;end</code> from
     * <code>source</code>.  The destination slice may be empty, in which case
     * the operation is an insertion, or the source slice may be empty,
     * in which case the operation is a deletion.
     * <p>
     * Before the change is committed, each filter that was set with
     * {@link #setFilters} is given the opportunity to modify the
     * <code>source</code> text.
     * <p>
     * If <code>source</code>
     * is Spanned, the spans from it are preserved into the Editable.
     * Existing spans within the Editable that entirely cover the replaced
     * range are retained, but any that were strictly within the range
     * that was replaced are removed. If the <code>source</code> contains a span
     * with {@link Spanned#SPAN_PARAGRAPH} flag, and it does not satisfy the
     * paragraph boundary constraint, it is not retained. As a special case, the
     * cursor position is preserved even when the entire range where it is located
     * is replaced.
     * @return  a reference to this object.
     *
     * @see Spanned#SPAN_PARAGRAPH
     */
    public Editable replace(int st, int en, CharSequence source, int start, int end);

    /**
     * Convenience for replace(st, en, text, 0, text.length())
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable replace(int st, int en, CharSequence text);

    /**
     * Convenience for replace(where, where, text, start, end)
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable insert(int where, CharSequence text, int start, int end);

    /**
     * Convenience for replace(where, where, text, 0, text.length());
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable insert(int where, CharSequence text);

    /**
     * Convenience for replace(st, en, "", 0, 0)
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable delete(int st, int en);

    /**
     * Convenience for replace(length(), length(), text, 0, text.length())
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable append(CharSequence text);

    /**
     * Convenience for replace(length(), length(), text, start, end)
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable append(CharSequence text, int start, int end);

    /**
     * Convenience for append(String.valueOf(text)).
     * @see #replace(int, int, CharSequence, int, int)
     */
    public Editable append(char text);

    /**
     * Convenience for replace(0, length(), "", 0, 0)
     * @see #replace(int, int, CharSequence, int, int)
     * Note that this clears the text, not the spans;
     * use {@link #clearSpans} if you need that.
     */
    public void clear();

    /**
     * Removes all spans from the Editable, as if by calling
     * {@link #removeSpan} on each of them.
     */
    public void clearSpans();

    /**
     * Sets the series of filters that will be called in succession
     * whenever the text of this Editable is changed, each of which has
     * the opportunity to limit or transform the text that is being inserted.
     */
    public void setFilters(InputFilter[] filters);

    /**
     * Returns the array of input filters that are currently applied
     * to changes to this Editable.
     */
    public InputFilter[] getFilters();

    /**
     * Factory used by TextView to create new Editables.  You can subclass
     * it to provide something other than SpannableStringBuilder.
     */
    public static class Factory {
        private static Editable.Factory sInstance = new Editable.Factory();

        /**
         * Returns the standard Editable Factory.
         */
        public static Editable.Factory getInstance() {
            return sInstance;
        }

        /**
         * Returns a new SpannedStringBuilder from the specified
         * CharSequence.  You can override this to provide
         * a different kind of Spanned.
         */
        public Editable newEditable(CharSequence source) {
            return new SpannableStringBuilder(source);
        }
    }
}

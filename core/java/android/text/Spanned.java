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
 * This is the interface for text that has markup objects attached to
 * ranges of it.  Not all text classes have mutable markup or text;
 * see {@link Spannable} for mutable markup and {@link Editable} for
 * mutable text.
 */
public interface Spanned
extends CharSequence
{
    /**
     * Bitmask of bits that are relevent for controlling point/mark behavior
     * of spans.
     *
     * MARK and POINT are conceptually located <i>between</i> two adjacent characters.
     * A MARK is "attached" to the character on the left hand side, while a POINT
     * tends to stick to the character on the right hand side.
     */
    public static final int SPAN_POINT_MARK_MASK = 0x33;
    
    /**
     * 0-length spans with type SPAN_MARK_MARK behave like text marks:
     * they remain at their original offset when text is inserted
     * at that offset. Conceptually, the text is added after the mark.
     */
    public static final int SPAN_MARK_MARK =   0x11;
    /**
     * SPAN_MARK_POINT is a synonym for {@link #SPAN_INCLUSIVE_INCLUSIVE}.
     */
    public static final int SPAN_MARK_POINT =  0x12;
    /**
     * SPAN_POINT_MARK is a synonym for {@link #SPAN_EXCLUSIVE_EXCLUSIVE}.
     */
    public static final int SPAN_POINT_MARK =  0x21;

    /**
     * 0-length spans with type SPAN_POINT_POINT behave like cursors:
     * they are pushed forward by the length of the insertion when text
     * is inserted at their offset.
     * The text is conceptually inserted before the point.
     */
    public static final int SPAN_POINT_POINT = 0x22;

    /**
     * SPAN_PARAGRAPH behaves like SPAN_INCLUSIVE_EXCLUSIVE
     * (SPAN_MARK_MARK), except that if either end of the span is
     * at the end of the buffer, that end behaves like _POINT
     * instead (so SPAN_INCLUSIVE_INCLUSIVE if it starts in the
     * middle and ends at the end, or SPAN_EXCLUSIVE_INCLUSIVE
     * if it both starts and ends at the end).
     * <p>
     * Its endpoints must be the start or end of the buffer or
     * immediately after a \n character, and if the \n
     * that anchors it is deleted, the endpoint is pulled to the
     * next \n that follows in the buffer (or to the end of
     * the buffer).
     */
    public static final int SPAN_PARAGRAPH =   0x33;

    /**
     * Non-0-length spans of type SPAN_INCLUSIVE_EXCLUSIVE expand
     * to include text inserted at their starting point but not at their
     * ending point.  When 0-length, they behave like marks.
     */
    public static final int SPAN_INCLUSIVE_EXCLUSIVE = SPAN_MARK_MARK;

    /**
     * Spans of type SPAN_INCLUSIVE_INCLUSIVE expand
     * to include text inserted at either their starting or ending point.
     */
    public static final int SPAN_INCLUSIVE_INCLUSIVE = SPAN_MARK_POINT;

    /**
     * Spans of type SPAN_EXCLUSIVE_EXCLUSIVE do not expand
     * to include text inserted at either their starting or ending point.
     * They can never have a length of 0 and are automatically removed
     * from the buffer if all the text they cover is removed.
     */
    public static final int SPAN_EXCLUSIVE_EXCLUSIVE = SPAN_POINT_MARK;

    /**
     * Non-0-length spans of type SPAN_EXCLUSIVE_INCLUSIVE expand
     * to include text inserted at their ending point but not at their
     * starting point.  When 0-length, they behave like points.
     */
    public static final int SPAN_EXCLUSIVE_INCLUSIVE = SPAN_POINT_POINT;

    /**
     * This flag is set on spans that are being used to apply temporary
     * styling information on the composing text of an input method, so that
     * they can be found and removed when the composing text is being
     * replaced.
     */
    public static final int SPAN_COMPOSING = 0x100;
    
    /**
     * This flag will be set for intermediate span changes, meaning there
     * is guaranteed to be another change following it.  Typically it is
     * used for {@link Selection} which automatically uses this with the first
     * offset it sets when updating the selection.
     */
    public static final int SPAN_INTERMEDIATE = 0x200;
    
    /**
     * The bits numbered SPAN_USER_SHIFT and above are available
     * for callers to use to store scalar data associated with their
     * span object.
     */
    public static final int SPAN_USER_SHIFT = 24;
    /**
     * The bits specified by the SPAN_USER bitfield are available
     * for callers to use to store scalar data associated with their
     * span object.
     */
    public static final int SPAN_USER = 0xFFFFFFFF << SPAN_USER_SHIFT;

    /**
     * The bits numbered just above SPAN_PRIORITY_SHIFT determine the order
     * of change notifications -- higher numbers go first.  You probably
     * don't need to set this; it is used so that when text changes, the
     * text layout gets the chance to update itself before any other
     * callbacks can inquire about the layout of the text.
     */
    public static final int SPAN_PRIORITY_SHIFT = 16;
    /**
     * The bits specified by the SPAN_PRIORITY bitmap determine the order
     * of change notifications -- higher numbers go first.  You probably
     * don't need to set this; it is used so that when text changes, the
     * text layout gets the chance to update itself before any other
     * callbacks can inquire about the layout of the text.
     */
    public static final int SPAN_PRIORITY = 0xFF << SPAN_PRIORITY_SHIFT;

    /**
     * Return an array of the markup objects attached to the specified
     * slice of this CharSequence and whose type is the specified type
     * or a subclass of it.  Specify Object.class for the type if you
     * want all the objects regardless of type.
     */
    public <T> T[] getSpans(int start, int end, Class<T> type);

    /**
     * Return the beginning of the range of text to which the specified
     * markup object is attached, or -1 if the object is not attached.
     */
    public int getSpanStart(Object tag);

    /**
     * Return the end of the range of text to which the specified
     * markup object is attached, or -1 if the object is not attached.
     */
    public int getSpanEnd(Object tag);

    /**
     * Return the flags that were specified when {@link Spannable#setSpan} was
     * used to attach the specified markup object, or 0 if the specified
     * object has not been attached.
     */
    public int getSpanFlags(Object tag);

    /**
     * Return the first offset greater than or equal to <code>start</code>
     * where a markup object of class <code>type</code> begins or ends,
     * or <code>limit</code> if there are no starts or ends greater than or
     * equal to <code>start</code> but less than <code>limit</code>.  Specify
     * <code>null</code> or Object.class for the type if you want every
     * transition regardless of type.
     */
    public int nextSpanTransition(int start, int limit, Class type);
}

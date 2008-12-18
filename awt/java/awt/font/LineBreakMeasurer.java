/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/*
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */

package java.awt.font;

import java.text.AttributedCharacterIterator; //???AWT: import java.text.BreakIterator;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The class LineBreakMeasurer provides methods to measure the graphical
 * representation of a text in order to determine where to add line breaks so
 * the resulting line of text fits its wrapping width. The wrapping width
 * defines the visual width of the paragraph.
 * 
 * @since Android 1.0
 */
public final class LineBreakMeasurer {

    /**
     * The tm.
     */
    private TextMeasurer tm = null;

    // ???AWT private BreakIterator bi = null;
    /**
     * The position.
     */
    private int position = 0;

    /**
     * The maxpos.
     */
    int maxpos = 0;

    /**
     * Instantiates a new LineBreakMeasurer object for the specified text.
     * 
     * @param text
     *            the AttributedCharacterIterator object which contains text
     *            with at least one character.
     * @param frc
     *            the FontRenderContext represented information about graphic
     *            device.
     */
    public LineBreakMeasurer(AttributedCharacterIterator text, FontRenderContext frc) {
        // ???AWT: this(text, BreakIterator.getLineInstance(), frc);
    }

    /*
     * ???AWT public LineBreakMeasurer( AttributedCharacterIterator text,
     * BreakIterator bi, FontRenderContext frc ) { tm = new TextMeasurer(text,
     * frc); this.bi = bi; this.bi.setText(text); position =
     * text.getBeginIndex(); maxpos = tm.aci.getEndIndex(); }
     */

    /**
     * Deletes a character from the specified position of the text, updates this
     * LineBreakMeasurer object.
     * 
     * @param newText
     *            the new text.
     * @param pos
     *            the position of the character which is deleted.
     */
    public void deleteChar(AttributedCharacterIterator newText, int pos) {
        tm.deleteChar(newText, pos);
        // ???AWT: bi.setText(newText);

        position = newText.getBeginIndex();

        maxpos--;
    }

    /**
     * Gets current position of this LineBreakMeasurer.
     * 
     * @return the current position of this LineBreakMeasurer
     */
    public int getPosition() {
        return position;
    }

    /**
     * Inserts a character at the specified position in the text, updates this
     * LineBreakMeasurer object.
     * 
     * @param newText
     *            the new text.
     * @param pos
     *            the position of the character which is inserted.
     */
    public void insertChar(AttributedCharacterIterator newText, int pos) {
        tm.insertChar(newText, pos);
        // ???AWT: bi.setText(newText);

        position = newText.getBeginIndex();

        maxpos++;
    }

    /**
     * Returns the next line of text, updates current position in this
     * LineBreakMeasurer.
     * 
     * @param wrappingWidth
     *            the maximum visible line width.
     * @param offsetLimit
     *            the limit point within the text indicating that no further
     *            text should be included on the line; the paragraph break.
     * @param requireNextWord
     *            if true, null is returned (the entire word at the current
     *            position does not fit within the wrapping width); if false, a
     *            valid layout is returned that includes at least the character
     *            at the current position.
     * @return the next TextLayout which begins at the current position and
     *         represents the next line of text with width wrappingWidth, null
     *         is returned if the entire word at the current position does not
     *         fit within the wrapping width.
     */
    public TextLayout nextLayout(float wrappingWidth, int offsetLimit, boolean requireNextWord) {
        if (position == maxpos) {
            return null;
        }

        int nextPosition = nextOffset(wrappingWidth, offsetLimit, requireNextWord);

        if (nextPosition == position) {
            return null;
        }
        TextLayout layout = tm.getLayout(position, nextPosition);
        position = nextPosition;
        return layout;
    }

    /**
     * Returns the next line of text.
     * 
     * @param wrappingWidth
     *            the maximum visible line width.
     * @return the next line of text.
     */
    public TextLayout nextLayout(float wrappingWidth) {
        return nextLayout(wrappingWidth, maxpos, false);
    }

    /**
     * Returns the end position of the next line of text.
     * 
     * @param wrappingWidth
     *            the maximum visible line width.
     * @return the end position of the next line of text.
     */
    public int nextOffset(float wrappingWidth) {
        return nextOffset(wrappingWidth, maxpos, false);
    }

    /**
     * Returns the end position of the next line of text.
     * 
     * @param wrappingWidth
     *            the maximum visible line width.
     * @param offsetLimit
     *            the limit point withing the text indicating that no further
     *            text should be included on the line; the paragraph break.
     * @param requireNextWord
     *            if true, the current position is returned if the entire next
     *            word does not fit within wrappingWidth; if false, the offset
     *            returned is at least one greater than the current position.
     * @return the end position of the next line of text.
     * @throws IllegalArgumentException
     *             if the offsetLimit is less than the current position.
     */
    public int nextOffset(float wrappingWidth, int offsetLimit, boolean requireNextWord) {
        if (offsetLimit <= position) {
            // awt.203=Offset limit should be greater than current position.
            throw new IllegalArgumentException(Messages.getString("awt.203")); //$NON-NLS-1$
        }

        if (position == maxpos) {
            return position;
        }

        int breakPos = tm.getLineBreakIndex(position, wrappingWidth);
        int correctedPos = breakPos;

        // This check is required because bi.preceding(maxpos) throws an
        // exception
        /*
         * ???AWT if (breakPos == maxpos) { correctedPos = maxpos; } else if
         * (Character.isWhitespace(bi.getText().setIndex(breakPos))) {
         * correctedPos = bi.following(breakPos); } else { correctedPos =
         * bi.preceding(breakPos); }
         */

        if (position >= correctedPos) {
            if (requireNextWord) {
                correctedPos = position;
            } else {
                correctedPos = Math.max(position + 1, breakPos);
            }
        }

        return Math.min(correctedPos, offsetLimit);
    }

    /**
     * Sets the new position of this LineBreakMeasurer.
     * 
     * @param pos
     *            the new position of this LineBreakMeasurer.
     */
    public void setPosition(int pos) {
        if (tm.aci.getBeginIndex() > pos || maxpos < pos) {
            // awt.33=index is out of range
            throw new IllegalArgumentException(Messages.getString("awt.33")); //$NON-NLS-1$
        }
        position = pos;
    }
}

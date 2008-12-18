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

import java.text.AttributedCharacterIterator;

import org.apache.harmony.awt.gl.font.TextMetricsCalculator;
import org.apache.harmony.awt.gl.font.TextRunBreaker;

/**
 * The TextMeasurer class provides utilities for line break operations.
 * 
 * @since Android 1.0
 */
public final class TextMeasurer implements Cloneable {

    /**
     * The aci.
     */
    AttributedCharacterIterator aci;

    /**
     * The frc.
     */
    FontRenderContext frc;

    /**
     * The breaker.
     */
    TextRunBreaker breaker = null;

    /**
     * The tmc.
     */
    TextMetricsCalculator tmc = null;

    /**
     * Instantiates a new text measurer from the specified text.
     * 
     * @param text
     *            the source text.
     * @param frc
     *            the FontRenderContext.
     */
    public TextMeasurer(AttributedCharacterIterator text, FontRenderContext frc) {
        this.aci = text;
        this.frc = frc;
        breaker = new TextRunBreaker(aci, this.frc);
        tmc = new TextMetricsCalculator(breaker);
    }

    /**
     * Replaces the current text with the new text, inserting a break character
     * at the specified insert position.
     * 
     * @param newParagraph
     *            the new paragraph text.
     * @param insertPos
     *            the position in the text where the character is inserted.
     */
    public void insertChar(AttributedCharacterIterator newParagraph, int insertPos) {
        AttributedCharacterIterator oldAci = aci;
        aci = newParagraph;
        if ((oldAci.getEndIndex() - oldAci.getBeginIndex())
                - (aci.getEndIndex() - aci.getBeginIndex()) != -1) {
            breaker = new TextRunBreaker(aci, this.frc);
            tmc = new TextMetricsCalculator(breaker);
        } else {
            breaker.insertChar(newParagraph, insertPos);
        }
    }

    /**
     * Replaces the current text with the new text and deletes a character at
     * the specified position.
     * 
     * @param newParagraph
     *            the paragraph text after deletion.
     * @param deletePos
     *            the position in the text where the character is removed.
     */
    public void deleteChar(AttributedCharacterIterator newParagraph, int deletePos) {
        AttributedCharacterIterator oldAci = aci;
        aci = newParagraph;
        if ((oldAci.getEndIndex() - oldAci.getBeginIndex())
                - (aci.getEndIndex() - aci.getBeginIndex()) != 1) {
            breaker = new TextRunBreaker(aci, this.frc);
            tmc = new TextMetricsCalculator(breaker);
        } else {
            breaker.deleteChar(newParagraph, deletePos);
        }
    }

    /**
     * Returns a copy of this object.
     * 
     * @return a copy of this object.
     */
    @Override
    protected Object clone() {
        return new TextMeasurer((AttributedCharacterIterator)aci.clone(), frc);
    }

    /**
     * Returns a TextLayout of the specified character range.
     * 
     * @param start
     *            the index of the first character.
     * @param limit
     *            the index after the last character.
     * @return a TextLayout for the characters beginning at "start" up to "end".
     */
    public TextLayout getLayout(int start, int limit) {
        breaker.pushSegments(start - aci.getBeginIndex(), limit - aci.getBeginIndex());

        breaker.createAllSegments();
        TextLayout layout = new TextLayout((TextRunBreaker)breaker.clone());

        breaker.popSegments();
        return layout;
    }

    /**
     * Returns the graphical width of a line beginning at "start" parameter and
     * including characters up to "end" parameter. "start" and "end" are
     * absolute indices, not relative to the "start" of the paragraph.
     * 
     * @param start
     *            the character index at which to start measuring.
     * @param end
     *            the character index at which to stop measuring.
     * @return the graphical width of a line beginning at "start" and including
     *         characters up to "end".
     */
    public float getAdvanceBetween(int start, int end) {
        breaker.pushSegments(start - aci.getBeginIndex(), end - aci.getBeginIndex());

        breaker.createAllSegments();
        float retval = tmc.createMetrics().getAdvance();

        breaker.popSegments();
        return retval;
    }

    /**
     * Returns the index of the first character which is not fit on a line
     * beginning at start and possible measuring up to maxAdvance in graphical
     * width.
     * 
     * @param start
     *            he character index at which to start measuring.
     * @param maxAdvance
     *            the graphical width in which the line must fit.
     * @return the index after the last character that is fit on a line
     *         beginning at start, which is not longer than maxAdvance in
     *         graphical width.
     */
    public int getLineBreakIndex(int start, float maxAdvance) {
        breaker.createAllSegments();
        return breaker.getLineBreakIndex(start - aci.getBeginIndex(), maxAdvance)
                + aci.getBeginIndex();
    }
}

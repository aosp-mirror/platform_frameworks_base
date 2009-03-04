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

package org.apache.harmony.awt.gl.font;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.TextHitInfo;
import java.awt.geom.Rectangle2D;

/**
 * Abstract class which represents the segment of the text with constant attributes
 * running in one direction (i.e. constant level).
 */
public abstract class TextRunSegment implements Cloneable {
    float x; // Calculated x location of this segment on the screen
    float y; // Calculated y location of this segment on the screen

    BasicMetrics metrics; // Metrics of this text run segment
    TextDecorator.Decoration decoration; // Underline, srikethrough, etc.
    Rectangle2D logicalBounds = null; // Logical bounding box for the segment
    Rectangle2D visualBounds = null; // Visual bounding box for the segment

    /**
     * Returns start index of the segment
     * @return start index
     */
    abstract int getStart();

    /**
     * Returns end index of the segment
     * @return end index
     */
    abstract int getEnd();

    /**
     * Returns the number of characters in the segment
     * @return number of characters
     */
    abstract int getLength();

    /**
     * Renders this text run segment
     * @param g2d - graphics to render to
     * @param xOffset - X offset from the graphics origin to the
     * origin of the text layout
     * @param yOffset - Y offset from the graphics origin to the
     * origin of the text layout
     */
    abstract void draw(Graphics2D g2d, float xOffset, float yOffset);

    /**
     * Creates black box bounds shape for the specified range
     * @param start - range sart
     * @param limit - range end
     * @return black box bounds shape
     */
    abstract Shape getCharsBlackBoxBounds(int start, int limit);

    /**
     * Returns the outline shape
     * @return outline
     */
    abstract Shape getOutline();

    /**
     * Returns visual bounds of this segment
     * @return visual bounds
     */
    abstract Rectangle2D getVisualBounds();

    /**
     * Returns logical bounds of this segment
     * @return logical bounds
     */
    abstract Rectangle2D getLogicalBounds();

    /**
     * Calculates advance of the segment
     * @return advance
     */
    abstract float getAdvance();

    /**
     * Calculates advance delta between two characters
     * @param start - 1st position
     * @param end - 2nd position
     * @return advance increment between specified positions
     */
    abstract float getAdvanceDelta(int start, int end);

    /**
     * Calculates index of the character which advance is equal to
     * the given. If the given advance is greater then the segment
     * advance it returns the position after the last character.
     * @param advance - given advance
     * @param start - character, from which to start measuring advance
     * @return character index
     */
    abstract int getCharIndexFromAdvance(float advance, int start);

    /**
     * Checks if the character doesn't contribute to the text advance
     * @param index - character index
     * @return true if the character has zero advance
     */
    abstract boolean charHasZeroAdvance(int index);

    /**
     * Calculates position of the character on the screen
     * @param index - character index
     * @return X coordinate of the character position
     */
    abstract float getCharPosition(int index);

    /**
     * Returns the advance of the individual character
     * @param index - character index
     * @return character advance
     */
    abstract float getCharAdvance(int index);

    /**
     * Creates text hit info from the hit position
     * @param x - X coordinate relative to the origin of the layout
     * @param y - Y coordinate relative to the origin of the layout
     * @return hit info
     */
    abstract TextHitInfo hitTest(float x, float y);

    /**
     * Collects justification information into JustificationInfo object
     * @param jInfo - JustificationInfo object
     */
    abstract void updateJustificationInfo(TextRunBreaker.JustificationInfo jInfo);

    /**
     * Performs justification of the segment.
     * Updates positions of individual characters.
     * @param jInfos - justification information, gathered by the previous passes
     * @return amount of growth or shrink of the segment
     */    
    abstract float doJustification(TextRunBreaker.JustificationInfo jInfos[]);

    @Override
    public abstract Object clone();
}

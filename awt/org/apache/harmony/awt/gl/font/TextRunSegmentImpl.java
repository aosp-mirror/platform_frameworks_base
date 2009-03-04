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
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 *
 */

package org.apache.harmony.awt.gl.font;

import java.awt.*;
import java.awt.font.*;
import java.awt.geom.Rectangle2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
// XXX - TODO - bidi not implemented yet
//import java.text.Bidi;
import java.util.Arrays;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * Date: Apr 25, 2005
 * Time: 4:33:18 PM
 *
 * This class contains the implementation of the behavior of the
 * text run segment with constant text attributes and direction.
 */
public class TextRunSegmentImpl {

    /**
     * This class contains basic information required for creation
     * of the glyph-based text run segment.
     */
    public static class TextSegmentInfo {
        // XXX - TODO - bidi not implemented yet
        //Bidi bidi;

        Font font;
        FontRenderContext frc;

        char text[];

        int start;
        int end;
        int length;

        int flags = 0;

        byte level = 0;

        TextSegmentInfo(
                byte level,
                Font font, FontRenderContext frc,
                char text[], int start, int end
        ) {
            this.font = font;
            this.frc = frc;
            this.text = text;
            this.start = start;
            this.end = end;
            this.level = level;
            length = end - start;
        }
    }

    /**
     * This class represents a simple text segment backed by the glyph vector
     */
    public static class TextRunSegmentCommon extends TextRunSegment {
        TextSegmentInfo info;
        private GlyphVector gv;
        private float advanceIncrements[];
        private int char2glyph[];
        private GlyphJustificationInfo gjis[]; // Glyph justification info

        TextRunSegmentCommon(TextSegmentInfo i, TextDecorator.Decoration d) {
            // XXX - todo - check support bidi
            i.flags &= ~0x09; // Clear bidi flags

            if ((i.level & 0x1) != 0) {
                i.flags |= Font.LAYOUT_RIGHT_TO_LEFT;
            }

            info = i;
            this.decoration = d;

            LineMetrics lm = i.font.getLineMetrics(i.text, i.start, i.end, i.frc);
            this.metrics = new BasicMetrics(lm, i.font);

            if (lm.getNumChars() != i.length) { // XXX todo - This should be handled
                // awt.41=Font returned unsupported type of line metrics. This case is known, but not supported yet.
                throw new UnsupportedOperationException(
                        Messages.getString("awt.41")); //$NON-NLS-1$
            }
        }

        @Override
        public Object clone() {
            return new TextRunSegmentCommon(info, decoration);
        }

        /**
         * Creates glyph vector from the managed text if needed
         * @return glyph vector
         */
        private GlyphVector getGlyphVector() {
            if (gv==null) {
                gv = info.font.layoutGlyphVector(
                        info.frc,
                        info.text,
                        info.start,
                        info.end - info.start, // NOTE: This parameter violates
                                               // spec, it is count,
                                               // not limit as spec states
                        info.flags
                );
            }

            return gv;
        }

        /**
         * Renders this text run segment
         * @param g2d - graphics to render to
         * @param xOffset - X offset from the graphics origin to the
         * origin of the text layout
         * @param yOffset - Y offset from the graphics origin to the
         * origin of the text layout
         */
        @Override
        void draw(Graphics2D g2d, float xOffset, float yOffset) {
            if (decoration == null) {
                g2d.drawGlyphVector(getGlyphVector(), xOffset + x, yOffset + y);
            } else {
                TextDecorator.prepareGraphics(this, g2d, xOffset, yOffset);
                g2d.drawGlyphVector(getGlyphVector(), xOffset + x, yOffset + y);
                TextDecorator.drawTextDecorations(this, g2d, xOffset, yOffset);
                TextDecorator.restoreGraphics(decoration, g2d);
            }
        }

        /**
         * Returns visual bounds of this segment
         * @return visual bounds
         */
        @Override
        Rectangle2D getVisualBounds() {
            if (visualBounds == null) {
                visualBounds =
                        TextDecorator.extendVisualBounds(
                                this,
                                getGlyphVector().getVisualBounds(),
                                decoration
                        );

                visualBounds.setRect(
                        x + visualBounds.getX(),
                        y + visualBounds.getY(),
                        visualBounds.getWidth(),
                        visualBounds.getHeight()
                );
            }

            return (Rectangle2D) visualBounds.clone();
        }

        /**
         * Returns logical bounds of this segment
         * @return logical bounds
         */
        @Override
        Rectangle2D getLogicalBounds() {
            if (logicalBounds == null) {
                logicalBounds = getGlyphVector().getLogicalBounds();

                logicalBounds.setRect(
                        x + logicalBounds.getX(),
                        y + logicalBounds.getY(),
                        logicalBounds.getWidth(),
                        logicalBounds.getHeight()
                );
            }

            return (Rectangle2D) logicalBounds.clone();
        }

        @Override
        float getAdvance() {
            return (float) getLogicalBounds().getWidth();
        }

        /**
         * Attemts to map each character to the corresponding advance increment
         */
        void initAdvanceMapping() {
            GlyphVector gv = getGlyphVector();
            int charIndicies[] = gv.getGlyphCharIndices(0, gv.getNumGlyphs(), null);
            advanceIncrements = new float[info.length];

            for (int i=0; i<charIndicies.length; i++) {
                advanceIncrements[charIndicies[i]] = gv.getGlyphMetrics(i).getAdvance();
            }
        }

        /**
         * Calculates advance delta between two characters
         * @param start - 1st position
         * @param end - 2nd position
         * @return advance increment between specified positions
         */
        @Override
        float getAdvanceDelta(int start, int end) {
            // Get coordinates in the segment context
            start -= info.start;
            end -= info.start;

            if (advanceIncrements == null) {
                initAdvanceMapping();
            }

            if (start < 0) {
                start = 0;
            }
            if (end > info.length) {
                end = info.length;
            }

            float sum = 0;
            for (int i=start; i<end; i++) {
                sum += advanceIncrements[i];
            }

            return sum;
        }

        /**
         * Calculates index of the character which advance is equal to
         * the given. If the given advance is greater then the segment
         * advance it returns the position after the last character.
         * @param advance - given advance
         * @param start - character, from which to start measuring advance
         * @return character index
         */
        @Override
        int getCharIndexFromAdvance(float advance, int start) {
            // XXX - todo - probably, possible to optimize
            // Add check if the given advance is greater then
            // the segment advance in the beginning. In this case
            // we don't need to run through all increments
            if (advanceIncrements == null) {
                initAdvanceMapping();
            }

            start -= info.start;

            if (start < 0) {
                start = 0;
            }

            int i = start;
            for (; i<info.length; i++) {
                advance -= advanceIncrements[i];
                if (advance < 0) {
                    break;
                }
            }

            return i + info.start;
        }

        @Override
        int getStart() {
            return info.start;
        }

        @Override
        int getEnd() {
            return info.end;
        }

        @Override
        int getLength() {
            return info.length;
        }

        /**
         * Attemts to create mapping of the characters to glyphs in the glyph vector.
         * @return array where for each character index stored corresponding glyph index
         */
        private int[] getChar2Glyph() {
            if (char2glyph == null) {
                GlyphVector gv = getGlyphVector();
                char2glyph = new int[info.length];
                Arrays.fill(char2glyph, -1);

                // Fill glyph indicies for first characters corresponding to each glyph
                int charIndicies[] = gv.getGlyphCharIndices(0, gv.getNumGlyphs(), null);
                for (int i=0; i<charIndicies.length; i++) {
                    char2glyph[charIndicies[i]] = i;
                }

                // If several characters corresponds to one glyph, create mapping for them
                // Suppose that these characters are going all together
                int currIndex = 0;
                for (int i=0; i<char2glyph.length; i++) {
                    if (char2glyph[i] < 0) {
                        char2glyph[i] = currIndex;
                    } else {
                        currIndex = char2glyph[i];
                    }
                }
            }

            return char2glyph;
        }

        /**
         * Creates black box bounds shape for the specified range
         * @param start - range sart
         * @param limit - range end
         * @return black box bounds shape
         */
        @Override
        Shape getCharsBlackBoxBounds(int start, int limit) {
            start -= info.start;
            limit -= info.start;

            if (limit > info.length) {
                limit = info.length;
            }

            GeneralPath result = new GeneralPath();

            int glyphIndex = 0;

            for (int i=start; i<limit; i++) {
                glyphIndex = getChar2Glyph()[i];
                result.append(getGlyphVector().getGlyphVisualBounds(glyphIndex), false);
            }

            // Shift to the segment's coordinates
            result.transform(AffineTransform.getTranslateInstance(x, y));

            return result;
        }

        /**
         * Calculates position of the character on the screen
         * @param index - character index
         * @return X coordinate of the character position
         */
        @Override
        float getCharPosition(int index) {
            index -= info.start;

            if (index > info.length) {
                index = info.length;
            }

            float result = 0;

            int glyphIndex = getChar2Glyph()[index];
            result = (float) getGlyphVector().getGlyphPosition(glyphIndex).getX();

            // Shift to the segment's coordinates
            result += x;

            return result;
        }

        /**
         * Returns the advance of the individual character
         * @param index - character index
         * @return character advance
         */
        @Override
        float getCharAdvance(int index) {
            if (advanceIncrements == null) {
                initAdvanceMapping();
            }

            return advanceIncrements[index - this.getStart()];
        }

        /**
         * Returns the outline shape
         * @return outline
         */
        @Override
        Shape getOutline() {
            AffineTransform t = AffineTransform.getTranslateInstance(x, y);
            return t.createTransformedShape(
                    TextDecorator.extendOutline(
                            this,
                            getGlyphVector().getOutline(),
                            decoration
                    )
            );
        }

        /**
         * Checks if the character doesn't contribute to the text advance
         * @param index - character index
         * @return true if the character has zero advance
         */
        @Override
        boolean charHasZeroAdvance(int index) {
            if (advanceIncrements == null) {
                initAdvanceMapping();
            }

            return advanceIncrements[index - this.getStart()] == 0;
        }

        /**
         * Creates text hit info from the hit position
         * @param hitX - X coordinate relative to the origin of the layout
         * @param hitY - Y coordinate relative to the origin of the layout
         * @return hit info
         */
        @Override
        TextHitInfo hitTest(float hitX, float hitY) {
            hitX -= x;

            float glyphPositions[] =
                    getGlyphVector().getGlyphPositions(0, info.length+1, null);

            int glyphIdx;
            boolean leading = false;
            for (glyphIdx = 1; glyphIdx <= info.length; glyphIdx++) {
                if (glyphPositions[(glyphIdx)*2] >= hitX) {
                    float advance =
                            glyphPositions[(glyphIdx)*2] - glyphPositions[(glyphIdx-1)*2];
                    leading = glyphPositions[(glyphIdx-1)*2] + advance/2 > hitX ? true : false;
                    glyphIdx--;
                    break;
                }
            }

            if (glyphIdx == info.length) {
                glyphIdx--;
            }

            int charIdx = getGlyphVector().getGlyphCharIndex(glyphIdx);

            return (leading) ^ ((info.level & 0x1) == 0x1)?
                    TextHitInfo.leading(charIdx + info.start) :
                    TextHitInfo.trailing(charIdx + info.start);
        }

        /**
         * Collects GlyphJustificationInfo objects from the glyph vector
         * @return array of all GlyphJustificationInfo objects
         */
        private GlyphJustificationInfo[] getGlyphJustificationInfos() {
            if (gjis == null) {
                GlyphVector gv = getGlyphVector();
                int nGlyphs = gv.getNumGlyphs();
                int charIndicies[] = gv.getGlyphCharIndices(0, nGlyphs, null);
                gjis = new GlyphJustificationInfo[nGlyphs];

                // Patch: temporary patch, getGlyphJustificationInfo is not implemented
                float fontSize = info.font.getSize2D();
                GlyphJustificationInfo defaultInfo =
                        new GlyphJustificationInfo(
                                0, // weight
                                false, GlyphJustificationInfo.PRIORITY_NONE, 0, 0, // grow
                                false, GlyphJustificationInfo.PRIORITY_NONE, 0, 0); // shrink
                GlyphJustificationInfo spaceInfo = new GlyphJustificationInfo(
                        fontSize, // weight
                        true, GlyphJustificationInfo.PRIORITY_WHITESPACE, 0, fontSize, // grow
                        true, GlyphJustificationInfo.PRIORITY_WHITESPACE, 0, fontSize); // shrink

                ////////
                // Temporary patch, getGlyphJustificationInfo is not implemented
                for (int i = 0; i < nGlyphs; i++) {
                    //gjis[i] = getGlyphVector().getGlyphJustificationInfo(i);

                    char c = info.text[charIndicies[i] + info.start];
                    if (Character.isWhitespace(c)) {
                        gjis[i] = spaceInfo;
                    } else {
                        gjis[i] = defaultInfo;
                    }
                    // End patch
                }
            }

            return gjis;
        }

        /**
         * Collects justification information into JustificationInfo object
         * @param jInfo - JustificationInfo object
         */
        @Override
        void updateJustificationInfo(TextRunBreaker.JustificationInfo jInfo) {
            int lastChar = Math.min(jInfo.lastIdx, info.end) - info.start;
            boolean haveFirst = info.start <= jInfo.firstIdx;
            boolean haveLast = info.end >= (jInfo.lastIdx + 1);

            int prevGlyphIdx = -1;
            int currGlyphIdx;

            if (jInfo.grow) { // Check how much we can grow/shrink on current priority level
                for (int i=0; i<lastChar; i++) {
                    currGlyphIdx = getChar2Glyph()[i];

                    if (currGlyphIdx == prevGlyphIdx) {
                        // Several chars could be represented by one glyph,
                        // suppose they are contiguous
                        continue;
                    }
                    prevGlyphIdx = currGlyphIdx;

                    GlyphJustificationInfo gji = getGlyphJustificationInfos()[currGlyphIdx];
                    if (gji.growPriority == jInfo.priority) {
                        jInfo.weight += gji.weight * 2;
                        jInfo.growLimit += gji.growLeftLimit;
                        jInfo.growLimit += gji.growRightLimit;
                        if (gji.growAbsorb) {
                            jInfo.absorbedWeight += gji.weight * 2;
                        }
                    }
                }
            } else {
                for (int i=0; i<lastChar; i++) {
                    currGlyphIdx = getChar2Glyph()[i];
                    if (currGlyphIdx == prevGlyphIdx) {
                        continue;
                    }
                    prevGlyphIdx = currGlyphIdx;

                    GlyphJustificationInfo gji = getGlyphJustificationInfos()[currGlyphIdx];
                    if (gji.shrinkPriority == jInfo.priority) {
                        jInfo.weight += gji.weight * 2;
                        jInfo.growLimit -= gji.shrinkLeftLimit;
                        jInfo.growLimit -= gji.shrinkRightLimit;
                        if (gji.shrinkAbsorb) {
                            jInfo.absorbedWeight += gji.weight * 2;
                        }
                    }
                }
            }

            if (haveFirst) {  // Don't add padding before first char
                GlyphJustificationInfo gji = getGlyphJustificationInfos()[getChar2Glyph()[0]];
                jInfo.weight -= gji.weight;
                if (jInfo.grow) {
                    jInfo.growLimit -= gji.growLeftLimit;
                    if (gji.growAbsorb) {
                        jInfo.absorbedWeight -= gji.weight;
                    }
                } else {
                    jInfo.growLimit += gji.shrinkLeftLimit;
                    if (gji.shrinkAbsorb) {
                        jInfo.absorbedWeight -= gji.weight;
                    }
                }
            }

            if (haveLast) {   // Don't add padding after last char
                GlyphJustificationInfo gji =
                        getGlyphJustificationInfos()[getChar2Glyph()[lastChar]];
                jInfo.weight -= gji.weight;
                if (jInfo.grow) {
                    jInfo.growLimit -= gji.growRightLimit;
                    if (gji.growAbsorb) {
                        jInfo.absorbedWeight -= gji.weight;
                    }
                } else {
                    jInfo.growLimit += gji.shrinkRightLimit;
                    if (gji.shrinkAbsorb) {
                        jInfo.absorbedWeight -= gji.weight;
                    }
                }
            }
        }

        /**
         * Performs justification of the segment.
         * Updates positions of individual characters.
         * @param jInfos - justification information, gathered by the previous passes
         * @return amount of growth or shrink of the segment
         */
        @Override
        float doJustification(TextRunBreaker.JustificationInfo jInfos[]) {
            int lastPriority =
                    jInfos[jInfos.length-1] == null ?
                    -1 : jInfos[jInfos.length-1].priority;

            // Get the highest priority
            int highestPriority = 0;
            for (; highestPriority<jInfos.length; highestPriority++) {
                if (jInfos[highestPriority] != null) {
                    break;
                }
            }

            if (highestPriority == jInfos.length) {
                return 0;
            }

            TextRunBreaker.JustificationInfo firstInfo = jInfos[highestPriority];
            TextRunBreaker.JustificationInfo lastInfo =
                    lastPriority > 0 ? jInfos[lastPriority] : null;

            boolean haveFirst = info.start <= firstInfo.firstIdx;
            boolean haveLast = info.end >= (firstInfo.lastIdx + 1);

            // Here we suppose that GLYPHS are ordered LEFT TO RIGHT
            int firstGlyph = haveFirst ?
                    getChar2Glyph()[firstInfo.firstIdx - info.start] :
                    getChar2Glyph()[0];

            int lastGlyph = haveLast ?
                    getChar2Glyph()[firstInfo.lastIdx - info.start] :
                    getChar2Glyph()[info.length - 1];
            if (haveLast) {
                lastGlyph--;
            }

            TextRunBreaker.JustificationInfo currInfo;
            float glyphOffset = 0;
            float positionIncrement = 0;
            float sideIncrement = 0;

            if (haveFirst) {  // Don't add padding before first char
                GlyphJustificationInfo gji = getGlyphJustificationInfos()[firstGlyph];
                currInfo = jInfos[gji.growPriority];
                if (currInfo != null) {
                    if (currInfo.useLimits) {
                        if (currInfo.absorb) {
                            glyphOffset += gji.weight * currInfo.absorbedGapPerUnit;
                        } else if (
                                lastInfo != null &&
                                lastInfo.priority == currInfo.priority
                        ) {
                            glyphOffset += gji.weight * lastInfo.absorbedGapPerUnit;
                        }
                        glyphOffset +=
                                firstInfo.grow ?
                                gji.growRightLimit :
                                -gji.shrinkRightLimit;
                    } else {
                        glyphOffset += gji.weight * currInfo.gapPerUnit;
                    }
                }

                firstGlyph++;
            }

            if (firstInfo.grow) {
                for (int i=firstGlyph; i<=lastGlyph; i++) {
                    GlyphJustificationInfo gji = getGlyphJustificationInfos()[i];
                    currInfo = jInfos[gji.growPriority];
                    if (currInfo == null) {
                        // We still have to increment glyph position
                        Point2D glyphPos = getGlyphVector().getGlyphPosition(i);
                        glyphPos.setLocation(glyphPos.getX() + glyphOffset, glyphPos.getY());
                        getGlyphVector().setGlyphPosition(i, glyphPos);

                        continue;
                    }

                    if (currInfo.useLimits) {
                        glyphOffset += gji.growLeftLimit;
                        if (currInfo.absorb) {
                            sideIncrement = gji.weight * currInfo.absorbedGapPerUnit;
                            glyphOffset += sideIncrement;
                            positionIncrement = glyphOffset;
                            glyphOffset += sideIncrement;
                        } else if (lastInfo != null && lastInfo.priority == currInfo.priority) {
                            sideIncrement = gji.weight * lastInfo.absorbedGapPerUnit;
                            glyphOffset += sideIncrement;
                            positionIncrement = glyphOffset;
                            glyphOffset += sideIncrement;
                        } else {
                            positionIncrement = glyphOffset;
                        }
                        glyphOffset += gji.growRightLimit;
                    } else {
                        sideIncrement = gji.weight * currInfo.gapPerUnit;
                        glyphOffset += sideIncrement;
                        positionIncrement = glyphOffset;
                        glyphOffset += sideIncrement;
                    }

                    Point2D glyphPos = getGlyphVector().getGlyphPosition(i);
                    glyphPos.setLocation(glyphPos.getX() + positionIncrement, glyphPos.getY());
                    getGlyphVector().setGlyphPosition(i, glyphPos);
                }
            } else {
                for (int i=firstGlyph; i<=lastGlyph; i++) {
                    GlyphJustificationInfo gji = getGlyphJustificationInfos()[i];
                    currInfo = jInfos[gji.shrinkPriority];
                    if (currInfo == null) {
                        // We still have to increment glyph position
                        Point2D glyphPos = getGlyphVector().getGlyphPosition(i);
                        glyphPos.setLocation(glyphPos.getX() + glyphOffset, glyphPos.getY());
                        getGlyphVector().setGlyphPosition(i, glyphPos);

                        continue;
                    }

                    if (currInfo.useLimits) {
                        glyphOffset -= gji.shrinkLeftLimit;
                        if (currInfo.absorb) {
                            sideIncrement = gji.weight * currInfo.absorbedGapPerUnit;
                            glyphOffset += sideIncrement;
                            positionIncrement = glyphOffset;
                            glyphOffset += sideIncrement;
                        } else if (lastInfo != null && lastInfo.priority == currInfo.priority) {
                            sideIncrement = gji.weight * lastInfo.absorbedGapPerUnit;
                            glyphOffset += sideIncrement;
                            positionIncrement = glyphOffset;
                            glyphOffset += sideIncrement;
                        } else {
                            positionIncrement = glyphOffset;
                        }
                        glyphOffset -= gji.shrinkRightLimit;
                    } else {
                        sideIncrement =  gji.weight * currInfo.gapPerUnit;
                        glyphOffset += sideIncrement;
                        positionIncrement = glyphOffset;
                        glyphOffset += sideIncrement;
                    }

                    Point2D glyphPos = getGlyphVector().getGlyphPosition(i);
                    glyphPos.setLocation(glyphPos.getX() + positionIncrement, glyphPos.getY());
                    getGlyphVector().setGlyphPosition(i, glyphPos);
                }
            }


            if (haveLast) {   // Don't add padding after last char
                lastGlyph++;

                GlyphJustificationInfo gji = getGlyphJustificationInfos()[lastGlyph];
                currInfo = jInfos[gji.growPriority];

                if (currInfo != null) {
                    if (currInfo.useLimits) {
                        glyphOffset += firstInfo.grow ? gji.growLeftLimit : -gji.shrinkLeftLimit;
                        if (currInfo.absorb) {
                            glyphOffset += gji.weight * currInfo.absorbedGapPerUnit;
                        } else if (lastInfo != null && lastInfo.priority == currInfo.priority) {
                            glyphOffset += gji.weight * lastInfo.absorbedGapPerUnit;
                        }
                    } else {
                        glyphOffset += gji.weight * currInfo.gapPerUnit;
                    }
                }

                // Ajust positions of all glyphs after last glyph
                for (int i=lastGlyph; i<getGlyphVector().getNumGlyphs()+1; i++) {
                    Point2D glyphPos = getGlyphVector().getGlyphPosition(i);
                    glyphPos.setLocation(glyphPos.getX() + glyphOffset, glyphPos.getY());
                    getGlyphVector().setGlyphPosition(i, glyphPos);
                }
            } else { // Update position after last glyph in glyph vector -
                // to get correct advance for it
                Point2D glyphPos = getGlyphVector().getGlyphPosition(lastGlyph+1);
                glyphPos.setLocation(glyphPos.getX() + glyphOffset, glyphPos.getY());
                getGlyphVector().setGlyphPosition(lastGlyph+1, glyphPos);
            }

            gjis = null; // We don't need justification infos any more
            // Also we have to reset cached bounds and metrics
            this.visualBounds = null;
            this.logicalBounds = null;

            return glyphOffset; // How much our segment grown or shrunk
        }
    }

    public static class TextRunSegmentGraphic extends TextRunSegment {
        GraphicAttribute ga;
        int start;
        int length;
        float fullAdvance;

        TextRunSegmentGraphic(GraphicAttribute attr, int len, int start) {
            this.start = start;
            length = len;
            ga = attr;
            metrics = new BasicMetrics(ga);
            fullAdvance = ga.getAdvance() * length;
        }

        @Override
        public Object clone() {
            return new TextRunSegmentGraphic(ga, length, start);
        }

        // Renders this text run segment
        @Override
        void draw(Graphics2D g2d, float xOffset, float yOffset) {
            if (decoration != null) {
                TextDecorator.prepareGraphics(this, g2d, xOffset, yOffset);
            }

            float xPos = x + xOffset;
            float yPos = y + yOffset;

            for (int i=0; i < length; i++) {
                ga.draw(g2d, xPos, yPos);
                xPos += ga.getAdvance();
            }

            if (decoration != null) {
                TextDecorator.drawTextDecorations(this, g2d, xOffset, yOffset);
                TextDecorator.restoreGraphics(decoration, g2d);
            }
        }

        // Returns visual bounds of this segment
        @Override
        Rectangle2D getVisualBounds() {
            if (visualBounds == null) {
                Rectangle2D bounds = ga.getBounds();

                // First and last chars can be out of logical bounds, so we calculate
                // (bounds.getWidth() - ga.getAdvance()) which is exactly the difference
                bounds.setRect(
                        bounds.getMinX() + x,
                        bounds.getMinY() + y,
                        bounds.getWidth() - ga.getAdvance() + getAdvance(),
                        bounds.getHeight()
                );
                visualBounds = TextDecorator.extendVisualBounds(this, bounds, decoration);
            }

            return (Rectangle2D) visualBounds.clone();
        }

        @Override
        Rectangle2D getLogicalBounds() {
            if (logicalBounds == null) {
                logicalBounds =
                        new Rectangle2D.Float(
                                x, y - metrics.ascent,
                                getAdvance(), metrics.ascent + metrics.descent
                        );
            }

            return (Rectangle2D) logicalBounds.clone();
        }

        @Override
        float getAdvance() {
            return fullAdvance;
        }

        @Override
        float getAdvanceDelta(int start, int end) {
            return ga.getAdvance() * (end - start);
        }

        @Override
        int getCharIndexFromAdvance(float advance, int start) {
            start -= this.start;

            if (start < 0) {
                start = 0;
            }

            int charOffset = (int) (advance/ga.getAdvance());

            if (charOffset + start > length) {
                return length + this.start;
            }
            return charOffset + start + this.start;
        }

        @Override
        int getStart() {
            return start;
        }

        @Override
        int getEnd() {
            return start + length;
        }

        @Override
        int getLength() {
            return length;
        }

        @Override
        Shape getCharsBlackBoxBounds(int start, int limit) {
            start -= this.start;
            limit -= this.start;

            if (limit > length) {
                limit = length;
            }

            Rectangle2D charBounds = ga.getBounds();
            charBounds.setRect(
                    charBounds.getX() + ga.getAdvance() * start + x,
                    charBounds.getY() + y,
                    charBounds.getWidth() + ga.getAdvance() * (limit - start),
                    charBounds.getHeight()
            );

            return charBounds;
        }

        @Override
        float getCharPosition(int index) {
            index -= start;
            if (index > length) {
                index = length;
            }

            return ga.getAdvance() * index + x;
        }

        @Override
        float getCharAdvance(int index) {
            return ga.getAdvance();
        }

        @Override
        Shape getOutline() {
            AffineTransform t = AffineTransform.getTranslateInstance(x, y);
            return t.createTransformedShape(
                    TextDecorator.extendOutline(this, getVisualBounds(), decoration)
            );
        }

        @Override
        boolean charHasZeroAdvance(int index) {
            return false;
        }

        @Override
        TextHitInfo hitTest(float hitX, float hitY) {
            hitX -= x;

            float tmp = hitX / ga.getAdvance();
            int hitIndex = Math.round(tmp);

            if (tmp > hitIndex) {
                return TextHitInfo.leading(hitIndex + this.start);
            }
            return TextHitInfo.trailing(hitIndex + this.start);
        }

        @Override
        void updateJustificationInfo(TextRunBreaker.JustificationInfo jInfo) {
            // Do nothing
        }

        @Override
        float doJustification(TextRunBreaker.JustificationInfo jInfos[]) {
            // Do nothing
            return 0;
        }
    }
}

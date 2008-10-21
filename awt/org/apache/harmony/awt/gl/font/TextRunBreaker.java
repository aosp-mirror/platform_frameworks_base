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


import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.im.InputMethodHighlight;
import java.awt.font.*;
import java.awt.*;
import java.text.AttributedCharacterIterator;
import java.text.Annotation;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.*;

import org.apache.harmony.awt.gl.font.TextDecorator.Decoration;
import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.misc.HashCode;
// TODO - bidi not implemented yet

/**
 * This class is responsible for breaking the text into the run segments
 * with constant font, style, other text attributes and direction.
 * It also stores the created text run segments and covers functionality
 * related to the operations on the set of segments, like calculating metrics,
 * rendering, justification, hit testing, etc.
 */
public class TextRunBreaker implements Cloneable {
    AttributedCharacterIterator aci;
    FontRenderContext frc;

    char[] text;

    byte[] levels;

    HashMap<Integer, Font> fonts;
    HashMap<Integer, Decoration> decorations;

    // Related to default font substitution
    int forcedFontRunStarts[];

    ArrayList<TextRunSegment> runSegments = new ArrayList<TextRunSegment>();

    // For fast retrieving of the segment containing
    // character with known logical index
    int logical2segment[];
    int segment2visual[]; // Visual order of segments TODO - implement
    int visual2segment[];
    int logical2visual[];
    int visual2logical[];

    SegmentsInfo storedSegments;
    private boolean haveAllSegments = false;
    int segmentsStart, segmentsEnd;

    float justification = 1.0f;

    public TextRunBreaker(AttributedCharacterIterator aci, FontRenderContext frc) {
        this.aci = aci;
        this.frc = frc;

        segmentsStart = aci.getBeginIndex();
        segmentsEnd = aci.getEndIndex();

        int len = segmentsEnd - segmentsStart;
        text = new char[len];
        aci.setIndex(segmentsEnd);
        while (len-- != 0) { // Going in backward direction is faster? Simplier checks here?
            text[len] = aci.previous();
        }

        createStyleRuns();
    }

    /**
     * Visual order of text segments may differ from the logical order.
     * This method calculates visual position of the segment from its logical position.
     * @param segmentNum - logical position of the segment
     * @return visual position of the segment
     */
    int getVisualFromSegmentOrder(int segmentNum) {
        return (segment2visual == null) ? segmentNum : segment2visual[segmentNum];
    }

    /**
     * Visual order of text segments may differ from the logical order.
     * This method calculates logical position of the segment from its visual position.
     * @param visual - visual position of the segment
     * @return logical position of the segment
     */
    int getSegmentFromVisualOrder(int visual) {
        return (visual2segment == null) ? visual : visual2segment[visual];
    }

    /**
     * Visual order of the characters may differ from the logical order.
     * This method calculates visual position of the character from its logical position.
     * @param logical - logical position of the character
     * @return visual position
     */
    int getVisualFromLogical(int logical) {
        return (logical2visual == null) ? logical : logical2visual[logical];
    }

    /**
     * Visual order of the characters may differ from the logical order.
     * This method calculates logical position of the character from its visual position.
     * @param visual - visual position
     * @return logical position
     */
    int getLogicalFromVisual(int visual) {
        return (visual2logical == null) ? visual : visual2logical[visual];
    }

    /**
     * Calculates the end index of the level run, limited by the given text run.
     * @param runStart - run start
     * @param runEnd - run end
     * @return end index of the level run
     */
    int getLevelRunLimit(int runStart, int runEnd) {
        if (levels == null) {
            return runEnd;
        }
        int endLevelRun = runStart + 1;
        byte level = levels[runStart];

        while (endLevelRun <= runEnd && levels[endLevelRun] == level) {
            endLevelRun++;
        }

        return endLevelRun;
    }

    /**
     * Adds InputMethodHighlight to the attributes
     * @param attrs - text attributes
     * @return patched text attributes
     */
    Map<? extends Attribute, ?> unpackAttributes(Map<? extends Attribute, ?> attrs) {
        if (attrs.containsKey(TextAttribute.INPUT_METHOD_HIGHLIGHT)) {
            Map<TextAttribute, ?> styles = null;

            Object val = attrs.get(TextAttribute.INPUT_METHOD_HIGHLIGHT);

            if (val instanceof Annotation) {
                val = ((Annotation) val).getValue();
            }

            if (val instanceof InputMethodHighlight) {
                InputMethodHighlight ihl = ((InputMethodHighlight) val);
                styles = ihl.getStyle();

                if (styles == null) {
                    Toolkit tk = Toolkit.getDefaultToolkit();
                    styles = tk.mapInputMethodHighlight(ihl);
                }
            }

            if (styles != null) {
                HashMap<Attribute, Object> newAttrs = new HashMap<Attribute, Object>();
                newAttrs.putAll(attrs);
                newAttrs.putAll(styles);
                return newAttrs;
            }
        }

        return attrs;
    }

    /**
     * Breaks the text into separate style runs.
     */
    void createStyleRuns() {
        // TODO - implement fast and simple case
        fonts = new HashMap<Integer, Font>();
        decorations = new HashMap<Integer, Decoration>();
        ////

        ArrayList<Integer> forcedFontRunStartsList = null;

        Map<? extends Attribute, ?> attributes = null;

        // Check justification attribute
        Object val = aci.getAttribute(TextAttribute.JUSTIFICATION);
        if (val != null) {
            justification = ((Float) val).floatValue();
        }

        for (
            int index = segmentsStart, nextRunStart = segmentsStart;
            index < segmentsEnd;
            index = nextRunStart, aci.setIndex(index)
           )  {
            nextRunStart = aci.getRunLimit();
            attributes = unpackAttributes(aci.getAttributes());

            TextDecorator.Decoration d = TextDecorator.getDecoration(attributes);
            decorations.put(new Integer(index), d);

            // Find appropriate font or place GraphicAttribute there

            // 1. Try to pick up CHAR_REPLACEMENT (compatibility)
            Font value = (Font)attributes.get(TextAttribute.CHAR_REPLACEMENT);

            if (value == null) {
                // 2. Try to Get FONT
                value = (Font)attributes.get(TextAttribute.FONT);

                if (value == null) {
                    // 3. Try to create font from FAMILY
                    if (attributes.get(TextAttribute.FAMILY) != null) {
                        value = Font.getFont(attributes);
                    }

                    if (value == null) {
                        // 4. No attributes found, using default.
                        if (forcedFontRunStartsList == null) {
                            forcedFontRunStartsList = new ArrayList<Integer>();
                        }
                        FontFinder.findFonts(
                                text,
                                index,
                                nextRunStart,
                                forcedFontRunStartsList,
                                fonts
                        );
                        value = fonts.get(new Integer(index));
                    }
                }
            }

            fonts.put(new Integer(index), value);
        }

        // We have added some default fonts, so we have some extra runs in text
        if (forcedFontRunStartsList != null) {
            forcedFontRunStarts = new int[forcedFontRunStartsList.size()];
            for (int i=0; i<forcedFontRunStartsList.size(); i++) {
                forcedFontRunStarts[i] =
                        forcedFontRunStartsList.get(i).intValue();
            }
        }
    }

    /**
     * Starting from the current position looks for the end of the text run with
     * constant text attributes.
     * @param runStart - start position
     * @param maxPos - position where to stop if no run limit found
     * @return style run limit
     */
    int getStyleRunLimit(int runStart, int maxPos) {
        try {
            aci.setIndex(runStart);
        } catch(IllegalArgumentException e) { // Index out of bounds
            if (runStart < segmentsStart) {
                aci.first();
            } else {
                aci.last();
            }
        }

        // If we have some extra runs we need to check for their limits
        if (forcedFontRunStarts != null) {
            for (int element : forcedFontRunStarts) {
                if (element > runStart) {
                    maxPos = Math.min(element, maxPos);
                    break;
                }
            }
        }

        return Math.min(aci.getRunLimit(), maxPos);
    }

    /**
     * Creates segments for the text run with
     * constant decoration, font and bidi level
     * @param runStart - run start
     * @param runEnd - run end
     */
    public void createSegments(int runStart, int runEnd) {
        int endStyleRun, endLevelRun;

        // TODO - update levels

        int pos = runStart, levelPos;

        aci.setIndex(pos);
        final int firstRunStart = aci.getRunStart();
        Object tdd = decorations.get(new Integer(firstRunStart));
        Object fontOrGAttr = fonts.get(new Integer(firstRunStart));

        logical2segment = new int[runEnd - runStart];

        do {
            endStyleRun = getStyleRunLimit(pos, runEnd);

            // runStart can be non-zero, but all arrays will be indexed from 0
            int ajustedPos = pos - runStart;
            int ajustedEndStyleRun = endStyleRun - runStart;
            levelPos = ajustedPos;
            do {
                endLevelRun = getLevelRunLimit(levelPos, ajustedEndStyleRun);

                if (fontOrGAttr instanceof GraphicAttribute) {
                    runSegments.add(
                        new TextRunSegmentImpl.TextRunSegmentGraphic(
                                (GraphicAttribute)fontOrGAttr,
                                endLevelRun - levelPos,
                                levelPos + runStart)
                    );
                    Arrays.fill(logical2segment, levelPos, endLevelRun, runSegments.size()-1);
                } else {
                    TextRunSegmentImpl.TextSegmentInfo i =
                            new TextRunSegmentImpl.TextSegmentInfo(
                                    levels == null ? 0 : levels[ajustedPos],
                                    (Font) fontOrGAttr,
                                    frc,
                                    text,
                                    levelPos + runStart,
                                    endLevelRun + runStart
                            );

                    runSegments.add(
                            new TextRunSegmentImpl.TextRunSegmentCommon(
                                    i,
                                    (TextDecorator.Decoration) tdd
                            )
                    );
                    Arrays.fill(logical2segment, levelPos, endLevelRun, runSegments.size()-1);
                }

                levelPos = endLevelRun;
            } while (levelPos < ajustedEndStyleRun);

            // Prepare next iteration
            pos = endStyleRun;
            tdd = decorations.get(new Integer(pos));
            fontOrGAttr = fonts.get(new Integer(pos));
        } while (pos < runEnd);
    }

    /**
     * Checks if text run segments are up to date and creates the new segments if not.
     */
    public void createAllSegments() {
        if ( !haveAllSegments &&
            (logical2segment == null ||
             logical2segment.length != segmentsEnd - segmentsStart)
        ) { // Check if we don't have all segments yet
            resetSegments();
            createSegments(segmentsStart, segmentsEnd);
        }

        haveAllSegments = true;
    }

    /**
     * Calculates position where line should be broken without
     * taking into account word boundaries.
     * @param start - start index
     * @param maxAdvance - maximum advance, width of the line
     * @return position where to break
     */
    public int getLineBreakIndex(int start, float maxAdvance) {
        int breakIndex;
        TextRunSegment s = null;

        for (
                int segmentIndex = logical2segment[start];
                segmentIndex < runSegments.size();
                segmentIndex++
           ) {
            s = runSegments.get(segmentIndex);
            breakIndex = s.getCharIndexFromAdvance(maxAdvance, start);

            if (breakIndex < s.getEnd()) {
                return breakIndex;
            }
            maxAdvance -= s.getAdvanceDelta(start, s.getEnd());
            start = s.getEnd();
        }

        return s.getEnd();
    }

    /**
     * Inserts character into the managed text.
     * @param newParagraph - new character iterator
     * @param insertPos - insertion position
     */
    public void insertChar(AttributedCharacterIterator newParagraph, int insertPos) {
        aci = newParagraph;

        char insChar = aci.setIndex(insertPos);

        Integer key = new Integer(insertPos);

        insertPos -= aci.getBeginIndex();

        char newText[] = new char[text.length + 1];
        System.arraycopy(text, 0, newText, 0, insertPos);
        newText[insertPos] = insChar;
        System.arraycopy(text, insertPos, newText, insertPos+1, text.length - insertPos);
        text = newText;

        if (aci.getRunStart() == key.intValue() && aci.getRunLimit() == key.intValue() + 1) {
            createStyleRuns(); // We have to create one new run, could be optimized
        } else {
            shiftStyleRuns(key, 1);
        }

        resetSegments();

        segmentsEnd++;
    }

    /**
     * Deletes character from the managed text.
     * @param newParagraph - new character iterator
     * @param deletePos - deletion position
     */
    public void deleteChar(AttributedCharacterIterator newParagraph, int deletePos) {
        aci = newParagraph;

        Integer key = new Integer(deletePos);

        deletePos -= aci.getBeginIndex();

        char newText[] = new char[text.length - 1];
        System.arraycopy(text, 0, newText, 0, deletePos);
        System.arraycopy(text, deletePos+1, newText, deletePos, newText.length - deletePos);
        text = newText;

        if (fonts.get(key) != null) {
            fonts.remove(key);
        }

        shiftStyleRuns(key, -1);

        resetSegments();

        segmentsEnd--;
    }

    /**
     * Shift all runs after specified position, needed to perfom insertion
     * or deletion in the managed text
     * @param pos - position where to start
     * @param shift - shift, could be negative
     */
    private void shiftStyleRuns(Integer pos, final int shift) {
        ArrayList<Integer> keys = new ArrayList<Integer>();

        Integer key, oldkey;
        for (Iterator<Integer> it = fonts.keySet().iterator(); it.hasNext(); ) {
            oldkey = it.next();
            if (oldkey.intValue() > pos.intValue()) {
                keys.add(oldkey);
            }
        }

        for (int i=0; i<keys.size(); i++) {
            oldkey = keys.get(i);
            key = new Integer(shift + oldkey.intValue());
            fonts.put(key, fonts.remove(oldkey));
            decorations.put(key, decorations.remove(oldkey));
        }
    }

    /**
     * Resets state of the class
     */
    private void resetSegments() {
        runSegments = new ArrayList<TextRunSegment>();
        logical2segment = null;
        segment2visual = null;
        visual2segment = null;
        levels = null;
        haveAllSegments = false;
    }

    private class SegmentsInfo {
        ArrayList<TextRunSegment> runSegments;
        int logical2segment[];
        int segment2visual[];
        int visual2segment[];
        byte levels[];
        int segmentsStart;
        int segmentsEnd;
    }

    /**
     * Saves the internal state of the class
     * @param newSegStart - new start index in the text
     * @param newSegEnd - new end index in the text
     */
    public void pushSegments(int newSegStart, int newSegEnd) {
        storedSegments = new SegmentsInfo();
        storedSegments.runSegments = this.runSegments;
        storedSegments.logical2segment = this.logical2segment;
        storedSegments.segment2visual = this.segment2visual;
        storedSegments.visual2segment = this.visual2segment;
        storedSegments.levels = this.levels;
        storedSegments.segmentsStart = segmentsStart;
        storedSegments.segmentsEnd = segmentsEnd;

        resetSegments();

        segmentsStart = newSegStart;
        segmentsEnd = newSegEnd;
    }

    /**
     * Restores the internal state of the class
     */
    public void popSegments() {
        if (storedSegments == null) {
            return;
        }

        this.runSegments = storedSegments.runSegments;
        this.logical2segment = storedSegments.logical2segment;
        this.segment2visual = storedSegments.segment2visual;
        this.visual2segment = storedSegments.visual2segment;
        this.levels = storedSegments.levels;
        this.segmentsStart = storedSegments.segmentsStart;
        this.segmentsEnd = storedSegments.segmentsEnd;
        storedSegments = null;

        if (runSegments.size() == 0 && logical2segment == null) {
            haveAllSegments = false;
        } else {
            haveAllSegments = true;
        }
    }

    @Override
    public Object clone() {
        try {
            TextRunBreaker res = (TextRunBreaker) super.clone();
            res.storedSegments = null;
            ArrayList<TextRunSegment> newSegments = new ArrayList<TextRunSegment>(runSegments.size());
            for (int i = 0; i < runSegments.size(); i++) {
                TextRunSegment seg =  runSegments.get(i);
                newSegments.add((TextRunSegment)seg.clone());
            }
            res.runSegments = newSegments;
            return res;
        } catch (CloneNotSupportedException e) {
            // awt.3E=Clone not supported
            throw new UnsupportedOperationException(Messages.getString("awt.3E")); //$NON-NLS-1$
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TextRunBreaker)) {
            return false;
        }

        TextRunBreaker br = (TextRunBreaker) obj;

        if (br.getACI().equals(aci) && br.frc.equals(frc)) {
            return true;
        }

        return false;
    }

    @Override
    public int hashCode() {
        return HashCode.combine(aci.hashCode(), frc.hashCode());
    }

    /**
     * Renders the managed text
     * @param g2d - graphics where to render
     * @param xOffset - offset in X direction to the upper left corner
     * of the layout from the origin of the graphics
     * @param yOffset - offset in Y direction to the upper left corner
     * of the layout from the origin of the graphics
     */
    public void drawSegments(Graphics2D g2d, float xOffset, float yOffset) {
        for (int i=0; i<runSegments.size(); i++) {
            runSegments.get(i).draw(g2d, xOffset, yOffset);
        }
    }

    /**
     * Creates the black box bounds shape
     * @param firstEndpoint - start position
     * @param secondEndpoint - end position
     * @return black box bounds shape
     */
    public Shape getBlackBoxBounds(int firstEndpoint, int secondEndpoint) {
        GeneralPath bounds = new GeneralPath();

        TextRunSegment segment;

        for (int idx = firstEndpoint; idx < secondEndpoint; idx=segment.getEnd()) {
            segment = runSegments.get(logical2segment[idx]);
            bounds.append(segment.getCharsBlackBoxBounds(idx, secondEndpoint), false);
        }

        return bounds;
    }

    /**
     * Creates visual bounds shape
     * @return visual bounds rectangle
     */
    public Rectangle2D getVisualBounds() {
        Rectangle2D bounds = null;

        for (int i=0; i<runSegments.size(); i++) {
            TextRunSegment s = runSegments.get(i);
            if (bounds != null) {
                Rectangle2D.union(bounds, s.getVisualBounds(), bounds);
            } else {
                bounds = s.getVisualBounds();
            }
        }

        return bounds;
    }

    /**
     * Creates logical bounds shape
     * @return logical bounds rectangle
     */
    public Rectangle2D getLogicalBounds() {
        Rectangle2D bounds = null;

        for (int i=0; i<runSegments.size(); i++) {
            TextRunSegment s = runSegments.get(i);
            if (bounds != null) {
                Rectangle2D.union(bounds, s.getLogicalBounds(), bounds);
            } else {
                bounds = s.getLogicalBounds();
            }
        }

        return bounds;
    }

    public int getCharCount() {
        return segmentsEnd - segmentsStart;
    }

    public byte getLevel(int idx) {
        if (levels == null) {
            return 0;
        }
        return levels[idx];
    }

    public int getBaseLevel() {
        return 0;
    }

    public boolean isLTR() {
        return true;
    }

    public char getChar(int index) {
        return text[index];
    }

    public AttributedCharacterIterator getACI() {
        return aci;
    }

    /**
     * Creates outline shape for the managed text
     * @return outline
     */
    public GeneralPath getOutline() {
        GeneralPath outline = new GeneralPath();

        TextRunSegment segment;

        for (int i = 0; i < runSegments.size(); i++) {
            segment = runSegments.get(i);
            outline.append(segment.getOutline(), false);
        }

        return outline;
    }

    /**
     * Calculates text hit info from the screen coordinates.
     * Current implementation totally ignores Y coordinate.
     * If X coordinate is outside of the layout boundaries, this
     * method returns leftmost or rightmost hit.
     * @param x - x coordinate of the hit
     * @param y - y coordinate of the hit
     * @return hit info
     */
    public TextHitInfo hitTest(float x, float y) {
        TextRunSegment segment;

        double endOfPrevSeg = -1;
        for (int i = 0; i < runSegments.size(); i++) {
            segment = runSegments.get(i);
            Rectangle2D bounds = segment.getVisualBounds();
            if ((bounds.getMinX() <= x && bounds.getMaxX() >= x) || // We are in the segment
               (endOfPrevSeg < x && bounds.getMinX() > x)) { // We are somewhere between the segments
                return segment.hitTest(x,y);
            }
            endOfPrevSeg = bounds.getMaxX();
        }

        return isLTR() ? TextHitInfo.trailing(text.length) : TextHitInfo.leading(0);
    }

    public float getJustification() {
        return justification;
    }

    /**
     * Calculates position of the last non whitespace character
     * in the managed text.
     * @return position of the last non whitespace character
     */
    public int getLastNonWhitespace() {
        int lastNonWhitespace = text.length;

        while (lastNonWhitespace >= 0) {
            lastNonWhitespace--;
            if (!Character.isWhitespace(text[lastNonWhitespace])) {
                break;
            }
        }

        return lastNonWhitespace;
    }

    /**
     * Performs justification of the managed text by changing segment positions
     * and positions of the glyphs inside of the segments.
     * @param gap - amount of space which should be compensated by justification
     */
    public void justify(float gap) {
        // Ignore trailing logical whitespace
        int firstIdx = segmentsStart;
        int lastIdx = getLastNonWhitespace() + segmentsStart;
        JustificationInfo jInfos[] = new JustificationInfo[5];
        float gapLeft = gap;

        int highestPriority = -1;
        // GlyphJustificationInfo.PRIORITY_KASHIDA is 0
        // GlyphJustificationInfo.PRIORITY_NONE is 3
        for (int priority = 0; priority <= GlyphJustificationInfo.PRIORITY_NONE + 1; priority++) {
            JustificationInfo jInfo = new JustificationInfo();
            jInfo.lastIdx = lastIdx;
            jInfo.firstIdx = firstIdx;
            jInfo.grow = gap > 0;
            jInfo.gapToFill = gapLeft;

            if (priority <= GlyphJustificationInfo.PRIORITY_NONE) {
                jInfo.priority = priority;
            } else {
                jInfo.priority = highestPriority; // Last pass
            }

            for (int i = 0; i < runSegments.size(); i++) {
                TextRunSegment segment = runSegments.get(i);
                if (segment.getStart() <= lastIdx) {
                    segment.updateJustificationInfo(jInfo);
                }
            }

            if (jInfo.priority == highestPriority) {
                jInfo.absorb = true;
                jInfo.absorbedWeight = jInfo.weight;
            }

            if (jInfo.weight != 0) {
                if (highestPriority < 0) {
                    highestPriority = priority;
                }
                jInfos[priority] = jInfo;
            } else {
                continue;
            }

            gapLeft -= jInfo.growLimit;

            if (((gapLeft > 0) ^ jInfo.grow) || gapLeft == 0) {
                gapLeft = 0;
                jInfo.gapPerUnit = jInfo.gapToFill/jInfo.weight;
                break;
            }
            jInfo.useLimits = true;

            if (jInfo.absorbedWeight > 0) {
                jInfo.absorb = true;
                jInfo.absorbedGapPerUnit =
                        (jInfo.gapToFill-jInfo.growLimit)/jInfo.absorbedWeight;
                break;
            }
        }

        float currJustificationOffset = 0;
        for (int i = 0; i < runSegments.size(); i++) {
            TextRunSegment segment =
                    runSegments.get(getSegmentFromVisualOrder(i));
            segment.x += currJustificationOffset;
            currJustificationOffset += segment.doJustification(jInfos);
        }

        justification = -1; // Make further justification impossible
    }

    /**
     * This class represents the information collected before the actual
     * justification is started and needed to perform the justification.
     * This information is closely related to the information stored in the
     * GlyphJustificationInfo for the text represented by glyph vectors.
     */
    class JustificationInfo {
        boolean grow;
        boolean absorb = false;
        boolean useLimits = false;
        int priority = 0;
        float weight = 0;
        float absorbedWeight = 0;
        float growLimit = 0;

        int lastIdx;
        int firstIdx;

        float gapToFill;

        float gapPerUnit = 0; // Precalculated value, gapToFill / weight
        float absorbedGapPerUnit = 0; // Precalculated value, gapToFill / weight
    }
}

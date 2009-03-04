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
 * @date: Jun 14, 2005
 */

package org.apache.harmony.awt.gl.font;

import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.*;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * This class provides functionality for creating caret and highlight shapes
 * (bidirectional text is also supported, but, unfortunately, not tested yet).
 */
public class CaretManager {
    private TextRunBreaker breaker;

    public CaretManager(TextRunBreaker breaker) {
        this.breaker = breaker;
    }

    /**
     * Checks if TextHitInfo is not out of the text range and throws the
     * IllegalArgumentException if it is.
     * @param info - text hit info
     */
    private void checkHit(TextHitInfo info) {
        int idx = info.getInsertionIndex();

        if (idx < 0 || idx > breaker.getCharCount()) {
            // awt.42=TextHitInfo out of range
            throw new IllegalArgumentException(Messages.getString("awt.42")); //$NON-NLS-1$
        }
    }

    /**
     * Calculates and returns visual position from the text hit info.
     * @param hitInfo - text hit info
     * @return visual index
     */
    private int getVisualFromHitInfo(TextHitInfo hitInfo) {
        final int idx = hitInfo.getCharIndex();

        if (idx >= 0 && idx < breaker.getCharCount()) {
            int visual = breaker.getVisualFromLogical(idx);
            // We take next character for (LTR char + TRAILING info) and (RTL + LEADING)
            if (hitInfo.isLeadingEdge() ^ ((breaker.getLevel(idx) & 0x1) == 0x0)) {
                visual++;
            }
            return visual;
        } else if (idx < 0) {
            return breaker.isLTR() ? 0: breaker.getCharCount();
        } else {
            return breaker.isLTR() ? breaker.getCharCount() : 0;
        }
    }

    /**
     * Calculates text hit info from the visual position
     * @param visual - visual position
     * @return text hit info
     */
    private TextHitInfo getHitInfoFromVisual(int visual) {
        final boolean first = visual == 0;

        if (!(first || visual == breaker.getCharCount())) {
            int logical = breaker.getLogicalFromVisual(visual);
            return (breaker.getLevel(logical) & 0x1) == 0x0 ?
                    TextHitInfo.leading(logical) : // LTR
                    TextHitInfo.trailing(logical); // RTL
        } else if (first) {
            return breaker.isLTR() ?
                    TextHitInfo.trailing(-1) :
                    TextHitInfo.leading(breaker.getCharCount());
        } else { // Last
            return breaker.isLTR() ?
                    TextHitInfo.leading(breaker.getCharCount()) :
                    TextHitInfo.trailing(-1);
        }
    }

    /**
     * Creates caret info. Required for the getCaretInfo
     * methods of the TextLayout
     * @param hitInfo - specifies caret position
     * @return caret info, see TextLayout.getCaretInfo documentation
     */
    public float[] getCaretInfo(TextHitInfo hitInfo) {
        checkHit(hitInfo);
        float res[] = new float[2];

        int visual = getVisualFromHitInfo(hitInfo);
        float advance, angle;
        TextRunSegment seg;

        if (visual < breaker.getCharCount()) {
            int logIdx = breaker.getLogicalFromVisual(visual);
            int segmentIdx = breaker.logical2segment[logIdx];
            seg = breaker.runSegments.get(segmentIdx);
            advance = seg.x + seg.getAdvanceDelta(seg.getStart(), logIdx);
            angle = seg.metrics.italicAngle;

        } else { // Last character
            int logIdx = breaker.getLogicalFromVisual(visual-1);
            int segmentIdx = breaker.logical2segment[logIdx];
            seg = breaker.runSegments.get(segmentIdx);
            advance = seg.x + seg.getAdvanceDelta(seg.getStart(), logIdx+1);
        }

        angle = seg.metrics.italicAngle;

        res[0] = advance;
        res[1] = angle;

        return res;
    }

    /**
     * Returns the next position to the right from the current caret position
     * @param hitInfo - current position
     * @return next position to the right
     */
    public TextHitInfo getNextRightHit(TextHitInfo hitInfo) {
        checkHit(hitInfo);
        int visual = getVisualFromHitInfo(hitInfo);

        if (visual == breaker.getCharCount()) {
            return null;
        }

        TextHitInfo newInfo;

        while(visual <= breaker.getCharCount()) {
            visual++;
            newInfo = getHitInfoFromVisual(visual);

            if (newInfo.getCharIndex() >= breaker.logical2segment.length) {
                return newInfo;
            }

            if (hitInfo.getCharIndex() >= 0) { // Don't check for leftmost info
                if (
                        breaker.logical2segment[newInfo.getCharIndex()] !=
                        breaker.logical2segment[hitInfo.getCharIndex()]
                ) {
                    return newInfo; // We crossed segment boundary
                }
            }

            TextRunSegment seg = breaker.runSegments.get(breaker.logical2segment[newInfo
                    .getCharIndex()]);
            if (!seg.charHasZeroAdvance(newInfo.getCharIndex())) {
                return newInfo;
            }
        }

        return null;
    }

    /**
     * Returns the next position to the left from the current caret position
     * @param hitInfo - current position
     * @return next position to the left
     */
    public TextHitInfo getNextLeftHit(TextHitInfo hitInfo) {
        checkHit(hitInfo);
        int visual = getVisualFromHitInfo(hitInfo);

        if (visual == 0) {
            return null;
        }

        TextHitInfo newInfo;

        while(visual >= 0) {
            visual--;
            newInfo = getHitInfoFromVisual(visual);

            if (newInfo.getCharIndex() < 0) {
                return newInfo;
            }

            // Don't check for rightmost info
            if (hitInfo.getCharIndex() < breaker.logical2segment.length) {
                if (
                        breaker.logical2segment[newInfo.getCharIndex()] !=
                        breaker.logical2segment[hitInfo.getCharIndex()]
                ) {
                    return newInfo; // We crossed segment boundary
                }
            }

            TextRunSegment seg = breaker.runSegments.get(breaker.logical2segment[newInfo
                    .getCharIndex()]);
            if (!seg.charHasZeroAdvance(newInfo.getCharIndex())) {
                return newInfo;
            }
        }

        return null;
    }

    /**
     * For each visual caret position there are two hits. For the simple LTR text one is
     * a trailing of the previous char and another is the leading of the next char. This
     * method returns the opposite hit for the given hit.
     * @param hitInfo - given hit
     * @return opposite hit
     */
    public TextHitInfo getVisualOtherHit(TextHitInfo hitInfo) {
        checkHit(hitInfo);

        int idx = hitInfo.getCharIndex();

        int resIdx;
        boolean resIsLeading;

        if (idx >= 0 && idx < breaker.getCharCount()) { // Hit info in the middle
            int visual = breaker.getVisualFromLogical(idx);

            // Char is LTR + LEADING info
            if (((breaker.getLevel(idx) & 0x1) == 0x0) ^ hitInfo.isLeadingEdge()) {
                visual++;
                if (visual == breaker.getCharCount()) {
                    if (breaker.isLTR()) {
                        resIdx = breaker.getCharCount();
                        resIsLeading = true;
                    } else {
                        resIdx = -1;
                        resIsLeading = false;
                    }
                } else {
                    resIdx = breaker.getLogicalFromVisual(visual);
                    if ((breaker.getLevel(resIdx) & 0x1) == 0x0) {
                        resIsLeading = true;
                    } else {
                        resIsLeading = false;
                    }
                }
            } else {
                visual--;
                if (visual == -1) {
                    if (breaker.isLTR()) {
                        resIdx = -1;
                        resIsLeading = false;
                    } else {
                        resIdx = breaker.getCharCount();
                        resIsLeading = true;
                    }
                } else {
                    resIdx = breaker.getLogicalFromVisual(visual);
                    if ((breaker.getLevel(resIdx) & 0x1) == 0x0) {
                        resIsLeading = false;
                    } else {
                        resIsLeading = true;
                    }
                }
            }
        } else if (idx < 0) { // before "start"
            if (breaker.isLTR()) {
                resIdx = breaker.getLogicalFromVisual(0);
                resIsLeading = (breaker.getLevel(resIdx) & 0x1) == 0x0; // LTR char?
            } else {
                resIdx = breaker.getLogicalFromVisual(breaker.getCharCount() - 1);
                resIsLeading = (breaker.getLevel(resIdx) & 0x1) != 0x0; // RTL char?
            }
        } else { // idx == breaker.getCharCount()
            if (breaker.isLTR()) {
                resIdx = breaker.getLogicalFromVisual(breaker.getCharCount() - 1);
                resIsLeading = (breaker.getLevel(resIdx) & 0x1) != 0x0; // LTR char?
            } else {
                resIdx = breaker.getLogicalFromVisual(0);
                resIsLeading = (breaker.getLevel(resIdx) & 0x1) == 0x0; // RTL char?
            }
        }

        return resIsLeading ? TextHitInfo.leading(resIdx) : TextHitInfo.trailing(resIdx);
    }

    public Line2D getCaretShape(TextHitInfo hitInfo, TextLayout layout) {
        return getCaretShape(hitInfo, layout, true, false, null);
    }

    /**
     * Creates a caret shape.
     * @param hitInfo - hit where to place a caret
     * @param layout - text layout
     * @param useItalic - unused for now, was used to create
     * slanted carets for italic text
     * @param useBounds - true if the cared should fit into the provided bounds
     * @param bounds - bounds for the caret
     * @return caret shape
     */
    public Line2D getCaretShape(
            TextHitInfo hitInfo, TextLayout layout,
            boolean useItalic, boolean useBounds, Rectangle2D bounds
    ) {
        checkHit(hitInfo);

        float x1, x2, y1, y2;

        int charIdx = hitInfo.getCharIndex();

        if (charIdx >= 0 && charIdx < breaker.getCharCount()) {
            TextRunSegment segment = breaker.runSegments.get(breaker.logical2segment[charIdx]);
            y1 = segment.metrics.descent;
            y2 = - segment.metrics.ascent - segment.metrics.leading;

            x1 = x2 = segment.getCharPosition(charIdx) + (hitInfo.isLeadingEdge() ?
                    0 : segment.getCharAdvance(charIdx));
            // Decided that straight cursor looks better even for italic fonts,
            // especially combined with highlighting
            /*
            // Not graphics, need to check italic angle and baseline
            if (layout.getBaseline() >= 0) {
                if (segment.metrics.italicAngle != 0 && useItalic) {
                    x1 -= segment.metrics.italicAngle * segment.metrics.descent;
                    x2 += segment.metrics.italicAngle *
                        (segment.metrics.ascent + segment.metrics.leading);

                    float baselineOffset =
                        layout.getBaselineOffsets()[layout.getBaseline()];
                    y1 += baselineOffset;
                    y2 += baselineOffset;
                }
            }
            */
        } else {
            y1 = layout.getDescent();
            y2 = - layout.getAscent() - layout.getLeading();
            x1 = x2 = ((breaker.getBaseLevel() & 0x1) == 0 ^ charIdx < 0) ?
                    layout.getAdvance() : 0;
        }

        if (useBounds) {
            y1 = (float) bounds.getMaxY();
            y2 = (float) bounds.getMinY();

            if (x2 > bounds.getMaxX()) {
                x1 = x2 = (float) bounds.getMaxX();
            }
            if (x1 < bounds.getMinX()) {
                x1 = x2 = (float) bounds.getMinX();
            }
        }

        return new Line2D.Float(x1, y1, x2, y2);
    }

    /**
     * Creates caret shapes for the specified offset. On the boundaries where
     * the text is changing its direction this method may return two shapes
     * for the strong and the weak carets, in other cases it would return one.
     * @param offset - offset in the text.
     * @param bounds - bounds to fit the carets into
     * @param policy - caret policy
     * @param layout - text layout
     * @return one or two caret shapes
     */
    public Shape[] getCaretShapes(
            int offset, Rectangle2D bounds,
            TextLayout.CaretPolicy policy, TextLayout layout
    ) {
        TextHitInfo hit1 = TextHitInfo.afterOffset(offset);
        TextHitInfo hit2 = getVisualOtherHit(hit1);

        Shape caret1 = getCaretShape(hit1, layout);

        if (getVisualFromHitInfo(hit1) == getVisualFromHitInfo(hit2)) {
            return new Shape[] {caret1, null};
        }
        Shape caret2 = getCaretShape(hit2, layout);

        TextHitInfo strongHit = policy.getStrongCaret(hit1, hit2, layout);
        return strongHit.equals(hit1) ?
                new Shape[] {caret1, caret2} :
                new Shape[] {caret2, caret1};
    }

    /**
     * Connects two carets to produce a highlight shape.
     * @param caret1 - 1st caret
     * @param caret2 - 2nd caret
     * @return highlight shape
     */
    GeneralPath connectCarets(Line2D caret1, Line2D caret2) {
        GeneralPath path = new GeneralPath(GeneralPath.WIND_NON_ZERO);
        path.moveTo((float) caret1.getX1(), (float) caret1.getY1());
        path.lineTo((float) caret2.getX1(), (float) caret2.getY1());
        path.lineTo((float) caret2.getX2(), (float) caret2.getY2());
        path.lineTo((float) caret1.getX2(), (float) caret1.getY2());

        path.closePath();

        return path;
    }

    /**
     * Creates a highlight shape from given two hits. This shape
     * will always be visually contiguous
     * @param hit1 - 1st hit
     * @param hit2 - 2nd hit
     * @param bounds - bounds to fit the shape into
     * @param layout - text layout
     * @return highlight shape
     */
    public Shape getVisualHighlightShape(
            TextHitInfo hit1, TextHitInfo hit2,
            Rectangle2D bounds, TextLayout layout
    ) {
        checkHit(hit1);
        checkHit(hit2);

        Line2D caret1 = getCaretShape(hit1, layout, false, true, bounds);
        Line2D caret2 = getCaretShape(hit2, layout, false, true, bounds);

        return connectCarets(caret1, caret2);
    }

    /**
     * Suppose that the user visually selected a block of text which has
     * several different levels (mixed RTL and LTR), so, in the logical
     * representation of the text this selection may be not contigous.
     * This methods returns a set of logical ranges for the arbitrary
     * visual selection represented by two hits.
     * @param hit1 - 1st hit
     * @param hit2 - 2nd hit
     * @return logical ranges for the selection
     */
    public int[] getLogicalRangesForVisualSelection(TextHitInfo hit1, TextHitInfo hit2) {
        checkHit(hit1);
        checkHit(hit2);

        int visual1 = getVisualFromHitInfo(hit1);
        int visual2 = getVisualFromHitInfo(hit2);

        if (visual1 > visual2) {
            int tmp = visual2;
            visual2 = visual1;
            visual1 = tmp;
        }

        // Max level is 255, so we don't need more than 512 entries
        int results[] = new int[512];

        int prevLogical, logical, runStart, numRuns = 0;

        logical = runStart = prevLogical = breaker.getLogicalFromVisual(visual1);

        // Get all the runs. We use the fact that direction is constant in all runs.
        for (int i=visual1+1; i<=visual2; i++) {
            logical = breaker.getLogicalFromVisual(i);
            int diff = logical-prevLogical;

            // Start of the next run encountered
            if (diff > 1 || diff < -1) {
                results[(numRuns)*2] = Math.min(runStart, prevLogical);
                results[(numRuns)*2 + 1] = Math.max(runStart, prevLogical);
                numRuns++;
                runStart = logical;
            }

            prevLogical = logical;
        }

        // The last unsaved run
        results[(numRuns)*2] = Math.min(runStart, logical);
        results[(numRuns)*2 + 1] = Math.max(runStart, logical);
        numRuns++;

        int retval[] = new int[numRuns*2];
        System.arraycopy(results, 0, retval, 0, numRuns*2);
        return retval;
    }

    /**
     * Creates a highlight shape from given two endpoints in the logical
     * representation. This shape is not always visually contiguous
     * @param firstEndpoint - 1st logical endpoint
     * @param secondEndpoint - 2nd logical endpoint
     * @param bounds - bounds to fit the shape into
     * @param layout - text layout
     * @return highlight shape
     */
    public Shape getLogicalHighlightShape(
            int firstEndpoint, int secondEndpoint,
            Rectangle2D bounds, TextLayout layout
    ) {
        GeneralPath res = new GeneralPath();

        for (int i=firstEndpoint; i<=secondEndpoint; i++) {
            int endRun = breaker.getLevelRunLimit(i, secondEndpoint);
            TextHitInfo hit1 = TextHitInfo.leading(i);
            TextHitInfo hit2 = TextHitInfo.trailing(endRun-1);

            Line2D caret1 = getCaretShape(hit1, layout, false, true, bounds);
            Line2D caret2 = getCaretShape(hit2, layout, false, true, bounds);

            res.append(connectCarets(caret1, caret2), false);

            i = endRun;
        }

        return res;
    }
}

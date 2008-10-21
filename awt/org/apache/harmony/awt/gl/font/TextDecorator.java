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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.TextAttribute;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.Map;

/**
 * This class is responsible for rendering text decorations like
 * underline, strikethrough, text with background, etc.
 */
public class TextDecorator {
    private static final TextDecorator inst = new TextDecorator();
    private TextDecorator() {}
    static TextDecorator getInstance() {
        return inst;
    }

    /**
     * This class encapsulates a set of decoration attributes for a single text run.
     */
    static class Decoration {
        private static final BasicStroke UNDERLINE_LOW_ONE_PIXEL_STROKE =
                new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10);

        private static final BasicStroke UNDERLINE_LOW_TWO_PIXEL_STROKE =
                new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10);

        private static final BasicStroke UNDERLINE_LOW_DOTTED_STROKE =
                new BasicStroke(
                        1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[] { 1, 1 }, 0
                );

        private static final BasicStroke UNDERLINE_LOW_DOTTED_STROKE2 =
                new BasicStroke(
                        1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[] { 1, 1 }, 1
                );

        private static final BasicStroke UNDERLINE_LOW_DASHED_STROKE =
                new BasicStroke(
                        1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[] { 4, 4 }, 0
                );

        boolean ulOn = false; // Have standard underline?
        BasicStroke ulStroke;

        BasicStroke imUlStroke;  // Stroke for INPUT_METHOD_UNDERLINE
        BasicStroke imUlStroke2; // Specially for UNDERLINE_LOW_GRAY

        boolean strikeThrough;
        BasicStroke strikeThroughStroke;

        boolean haveStrokes = false; // Strokes already created?

        boolean swapBfFg;
        Paint bg; // background color
        Paint fg; // foreground color

        Paint graphicsPaint; // Slot for saving current paint

        Decoration(
                Integer imUl,
                boolean swap,
                boolean sth,
                Paint bg, Paint fg,
                boolean ulOn) {

            if (imUl != null) {
                // Determine which stroke to use
                if (imUl == TextAttribute.UNDERLINE_LOW_ONE_PIXEL) {
                    this.imUlStroke = Decoration.UNDERLINE_LOW_ONE_PIXEL_STROKE;
                } else if (imUl == TextAttribute.UNDERLINE_LOW_TWO_PIXEL) {
                    this.imUlStroke = Decoration.UNDERLINE_LOW_TWO_PIXEL_STROKE;
                } else if (imUl == TextAttribute.UNDERLINE_LOW_DOTTED) {
                    this.imUlStroke = Decoration.UNDERLINE_LOW_DOTTED_STROKE;
                } else if (imUl == TextAttribute.UNDERLINE_LOW_GRAY) {
                    this.imUlStroke = Decoration.UNDERLINE_LOW_DOTTED_STROKE;
                    this.imUlStroke2 = Decoration.UNDERLINE_LOW_DOTTED_STROKE2;
                } else if (imUl == TextAttribute.UNDERLINE_LOW_DASHED) {
                    this.imUlStroke = Decoration.UNDERLINE_LOW_DASHED_STROKE;
                }
            }

            this.ulOn = ulOn; // Has underline
            this.swapBfFg = swap;
            this.strikeThrough = sth;
            this.bg = bg;
            this.fg = fg;
        }

        /**
         * Creates strokes of proper width according to the info
         * stored in the BasicMetrics
         * @param metrics - basic metrics
         */
        private void getStrokes(BasicMetrics metrics) {
            if (!haveStrokes) {
                if (strikeThrough) {
                    strikeThroughStroke =
                            new BasicStroke(
                                    metrics.strikethroughThickness,
                                    BasicStroke.CAP_BUTT,
                                    BasicStroke.JOIN_MITER,
                                    10
                            );
                }

                if (ulOn) {
                    ulStroke =
                            new BasicStroke(
                                    metrics.underlineThickness,
                                    BasicStroke.CAP_BUTT,
                                    BasicStroke.JOIN_MITER,
                                    10
                            );
                }

                haveStrokes = true;
            }
        }
    }

    /**
     * Creates Decoration object from the set of text attributes
     * @param attributes - text attributes
     * @return Decoration object
     */
    static Decoration getDecoration(Map<? extends Attribute, ?> attributes) {
        if (attributes == null) {
            return null; // It is for plain text
        }

        Object underline = attributes.get(TextAttribute.UNDERLINE);
        boolean hasStandardUnderline = underline == TextAttribute.UNDERLINE_ON;

        Object imUnderline = attributes.get(TextAttribute.INPUT_METHOD_UNDERLINE);
        Integer imUl = (Integer) imUnderline;

        boolean swapBgFg =
                TextAttribute.SWAP_COLORS_ON.equals(
                        attributes.get(TextAttribute.SWAP_COLORS)
                );

        boolean strikeThrough =
                TextAttribute.STRIKETHROUGH_ON.equals(
                        attributes.get(TextAttribute.STRIKETHROUGH)
                );

        Paint fg = (Paint) attributes.get(TextAttribute.FOREGROUND);
        Paint bg = (Paint) attributes.get(TextAttribute.BACKGROUND);

        if (
                !hasStandardUnderline &&
                imUnderline == null &&
                fg == null &&
                bg == null &&
                !swapBgFg &&
                !strikeThrough
        ) {
            return null;
        }
        return new Decoration(imUl, swapBgFg, strikeThrough, bg, fg, hasStandardUnderline);
    }

    /**
     * Fills the background before drawing if needed.
     * 
     * @param trs - text segment
     * @param g2d - graphics to draw to
     * @param xOffset - offset in X direction to the upper left corner of the
     *        layout from the origin of the graphics
     * @param yOffset - offset in Y direction to the upper left corner of the
     *        layout from the origin of the graphics
     */
    static void prepareGraphics(
            TextRunSegment trs, Graphics2D g2d,
            float xOffset, float yOffset
    ) {
        Decoration d = trs.decoration;

        if (d.fg == null && d.bg == null && d.swapBfFg == false) {
            return; // Nothing to do
        }

        d.graphicsPaint = g2d.getPaint();

        if (d.fg == null) {
            d.fg = d.graphicsPaint;
        }

        if (d.swapBfFg) {
            // Fill background area
            g2d.setPaint(d.fg);
            Rectangle2D bgArea = trs.getLogicalBounds();
            Rectangle2D toFill =
                    new Rectangle2D.Double(
                            bgArea.getX() + xOffset,
                            bgArea.getY() + yOffset,
                            bgArea.getWidth(),
                            bgArea.getHeight()
                    );
            g2d.fill(toFill);

            // Set foreground color
            g2d.setPaint(d.bg == null ? Color.WHITE : d.bg);
        } else {
            if (d.bg != null) { // Fill background area
                g2d.setPaint(d.bg);
                Rectangle2D bgArea = trs.getLogicalBounds();
                Rectangle2D toFill =
                        new Rectangle2D.Double(
                                bgArea.getX() + xOffset,
                                bgArea.getY() + yOffset,
                                bgArea.getWidth(),
                                bgArea.getHeight()
                        );
                g2d.fill(toFill);
            }

            // Set foreground color
            g2d.setPaint(d.fg);
        }
    }

    /**
     * Restores the original state of the graphics if needed
     * @param d - decoration
     * @param g2d - graphics
     */
    static void restoreGraphics(Decoration d, Graphics2D g2d) {
        if (d.fg == null && d.bg == null && d.swapBfFg == false) {
            return; // Nothing to do
        }

        g2d.setPaint(d.graphicsPaint);
    }

    /**
     * Renders the text decorations
     * @param trs - text run segment
     * @param g2d - graphics to render to
     * @param xOffset - offset in X direction to the upper left corner
     * of the layout from the origin of the graphics
     * @param yOffset - offset in Y direction to the upper left corner
     * of the layout from the origin of the graphics
     */
    static void drawTextDecorations(
            TextRunSegment trs, Graphics2D g2d,
            float xOffset, float yOffset
    ) {
        Decoration d = trs.decoration;

        if (!d.ulOn && d.imUlStroke == null && !d.strikeThrough) {
            return; // Nothing to do
        }

        float left = xOffset + (float) trs.getLogicalBounds().getMinX();
        float right = xOffset + (float) trs.getLogicalBounds().getMaxX();

        Stroke savedStroke = g2d.getStroke();

        d.getStrokes(trs.metrics);

        if (d.strikeThrough) {
            float y = trs.y + yOffset + trs.metrics.strikethroughOffset;
            g2d.setStroke(d.strikeThroughStroke);
            g2d.draw(new Line2D.Float(left, y, right, y));
        }

        if (d.ulOn) {
            float y = trs.y + yOffset + trs.metrics.underlineOffset;
            g2d.setStroke(d.ulStroke);
            g2d.draw(new Line2D.Float(left, y, right, y));
        }

        if (d.imUlStroke != null) {
            float y = trs.y + yOffset + trs.metrics.underlineOffset;
            g2d.setStroke(d.imUlStroke);
            g2d.draw(new Line2D.Float(left, y, right, y));
            if (d.imUlStroke2 != null) {
                y++;
                g2d.setStroke(d.imUlStroke2);
                g2d.draw(new Line2D.Float(left, y, right, y));
            }
        }

        g2d.setStroke(savedStroke);
    }

    /**
     * Extends the visual bounds of the text run segment to
     * include text decorations.
     * @param trs - text segment
     * @param segmentBounds - bounds of the undecorated text
     * @param d - decoration
     * @return extended bounds
     */
    static Rectangle2D extendVisualBounds(
            TextRunSegment trs,
            Rectangle2D segmentBounds,
            Decoration d
    ) {
        if (d == null) {
            return segmentBounds;
        }
        double minx = segmentBounds.getMinX();
        double miny = segmentBounds.getMinY();
        double maxx = segmentBounds.getMaxX();
        double maxy = segmentBounds.getMaxY();

        Rectangle2D lb = trs.getLogicalBounds();

        if (d.swapBfFg || d.bg != null) {
            minx = Math.min(lb.getMinX() - trs.x, minx);
            miny = Math.min(lb.getMinY() - trs.y, miny);
            maxx = Math.max(lb.getMaxX() - trs.x, maxx);
            maxy = Math.max(lb.getMaxY() - trs.y, maxy);
        }

        if (d.ulOn || d.imUlStroke != null || d.strikeThrough) {
            minx = Math.min(lb.getMinX() - trs.x, minx);
            maxx = Math.max(lb.getMaxX() - trs.x, maxx);

            d.getStrokes(trs.metrics);

            if (d.ulStroke != null) {
                maxy = Math.max(
                        maxy,
                        trs.metrics.underlineOffset +
                        d.ulStroke.getLineWidth()
                );
            }

            if (d.imUlStroke != null) {
                maxy = Math.max(
                        maxy,
                        trs.metrics.underlineOffset +
                        d.imUlStroke.getLineWidth() +
                        (d.imUlStroke2 == null ? 0 : d.imUlStroke2.getLineWidth())
                );
            }
        }

        return new Rectangle2D.Double(minx, miny, maxx-minx, maxy-miny);
    }

    /**
     * Extends the outline of the text run segment to
     * include text decorations.
     * @param trs - text segment
     * @param segmentOutline - outline of the undecorated text
     * @param d - decoration
     * @return extended outline
     */
    static Shape extendOutline(
            TextRunSegment trs,
            Shape segmentOutline,
            Decoration d
    ) {
        if (d == null || !d.ulOn && d.imUlStroke == null && !d.strikeThrough) {
            return segmentOutline; // Nothing to do
        }

        Area res = new Area(segmentOutline);

        float left = (float) trs.getLogicalBounds().getMinX() - trs.x;
        float right = (float) trs.getLogicalBounds().getMaxX() - trs.x;

        d.getStrokes(trs.metrics);

        if (d.strikeThrough) {
            float y = trs.metrics.strikethroughOffset;
            res.add(new Area(d.strikeThroughStroke.createStrokedShape(
                    new Line2D.Float(left, y, right, y)
            )));
        }

        if (d.ulOn) {
            float y = trs.metrics.underlineOffset;
            res.add(new Area(d.ulStroke.createStrokedShape(
                    new Line2D.Float(left, y, right, y)
            )));
        }

        if (d.imUlStroke != null) {
            float y = trs.metrics.underlineOffset;
            res.add(new Area(d.imUlStroke.createStrokedShape(
                    new Line2D.Float(left, y, right, y)
            )));

            if (d.imUlStroke2 != null) {
                y++;
                res.add(new Area(d.imUlStroke2.createStrokedShape(
                        new Line2D.Float(left, y, right, y)
                )));
            }
        }

        return res;
    }
}

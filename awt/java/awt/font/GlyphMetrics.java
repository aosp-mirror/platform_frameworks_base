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
 * @author Ilya S. Okomin
 * @version $Revision$
 */

package java.awt.font;

import java.awt.geom.Rectangle2D;

/**
 * The GlyphMetrics class provides information about the size and shape of a
 * single glyph. Each glyph has information to specify whether its baseline is
 * horizontal or vertical as well as information on how it interacts with other
 * characters in a text, given as one of the following types: STANDARD,
 * LIGATURE, COMBINING, or COMPONENT.
 * 
 * @since Android 1.0
 */
public final class GlyphMetrics {

    // advance width of the glyph character cell
    /**
     * The advance x.
     */
    private float advanceX;

    // advance height of the glyph character cell
    /**
     * The advance y.
     */
    private float advanceY;

    // flag if the glyph horizontal
    /**
     * The horizontal.
     */
    private boolean horizontal;

    // glyph type code
    /**
     * The glyph type.
     */
    private byte glyphType;

    // bounding box for outline of the glyph
    /**
     * The bounds.
     */
    private Rectangle2D.Float bounds;

    /**
     * The Constant STANDARD indicates a glyph that represents a single
     * character.
     */
    public static final byte STANDARD = 0;

    /**
     * The Constant LIGATURE indicates a glyph that represents multiple
     * characters as a ligature.
     */
    public static final byte LIGATURE = 1;

    /**
     * The Constant COMBINING indicates a glyph which has no caret position
     * between glyphs (for example umlaut).
     */
    public static final byte COMBINING = 2;

    /**
     * The Constant COMPONENT indicates a glyph with no corresponding character
     * in the backing store.
     */
    public static final byte COMPONENT = 3;

    /**
     * The Constant WHITESPACE indicates a glyph without visual representation.
     */
    public static final byte WHITESPACE = 4;

    /**
     * Instantiates a new GlyphMetrics object with the specified parameters.
     * 
     * @param horizontal
     *            specifies if metrics are for a horizontal baseline (true
     *            value), or a vertical baseline (false value).
     * @param advanceX
     *            the X component of the glyph's advance.
     * @param advanceY
     *            the Y component of the glyph's advance.
     * @param bounds
     *            the glyph's bounds.
     * @param glyphType
     *            the glyph's type.
     */
    public GlyphMetrics(boolean horizontal, float advanceX, float advanceY, Rectangle2D bounds,
            byte glyphType) {
        this.horizontal = horizontal;
        this.advanceX = advanceX;
        this.advanceY = advanceY;

        this.bounds = new Rectangle2D.Float();
        this.bounds.setRect(bounds);

        this.glyphType = glyphType;
    }

    /**
     * Instantiates a new horizontal GlyphMetrics with the specified parameters.
     * 
     * @param advanceX
     *            the X component of the glyph's advance.
     * @param bounds
     *            the glyph's bounds.
     * @param glyphType
     *            the glyph's type.
     */
    public GlyphMetrics(float advanceX, Rectangle2D bounds, byte glyphType) {
        this.advanceX = advanceX;
        this.advanceY = 0;

        this.horizontal = true;

        this.bounds = new Rectangle2D.Float();
        this.bounds.setRect(bounds);

        this.glyphType = glyphType;
    }

    /**
     * Gets the glyph's bounds.
     * 
     * @return glyph's bounds.
     */
    public Rectangle2D getBounds2D() {
        return (Rectangle2D.Float)this.bounds.clone();
    }

    /**
     * Checks if this glyph is whitespace or not.
     * 
     * @return true, if this glyph is whitespace, false otherwise.
     */
    public boolean isWhitespace() {
        return ((this.glyphType & 4) == WHITESPACE);
    }

    /**
     * Checks if this glyph is standard or not.
     * 
     * @return true, if this glyph is standard, false otherwise.
     */
    public boolean isStandard() {
        return ((this.glyphType & 3) == STANDARD);
    }

    /**
     * Checks if this glyph is ligature or not.
     * 
     * @return true, if this glyph is ligature, false otherwise.
     */
    public boolean isLigature() {
        return ((this.glyphType & 3) == LIGATURE);
    }

    /**
     * Checks if this glyph is component or not.
     * 
     * @return true, if this glyph is component, false otherwise.
     */
    public boolean isComponent() {
        return ((this.glyphType & 3) == COMPONENT);
    }

    /**
     * Checks if this glyph is combining or not.
     * 
     * @return true, if this glyph is combining, false otherwise.
     */
    public boolean isCombining() {
        return ((this.glyphType & 3) == COMBINING);
    }

    /**
     * Gets the glyph's type.
     * 
     * @return the glyph's type.
     */
    public int getType() {
        return this.glyphType;
    }

    /**
     * Gets the distance from the right (for horizontal) or bottom (for
     * vertical) of the glyph bounds to the advance.
     * 
     * @return the distance from the right (for horizontal) or bottom (for
     *         vertical) of the glyph bounds to the advance.
     */
    public float getRSB() {
        if (this.horizontal) {
            return this.advanceX - this.bounds.x - (float)this.bounds.getWidth();
        }
        return this.advanceY - this.bounds.y - (float)this.bounds.getHeight();
    }

    /**
     * Gets the distance from 0, 0 to the left (for horizontal) or top (for
     * vertical) of the glyph bounds.
     * 
     * @return the distance from 0, 0 to the left (for horizontal) or top (for
     *         vertical) of the glyph bounds.
     */
    public float getLSB() {
        if (this.horizontal) {
            return this.bounds.x;
        }
        return this.bounds.y;
    }

    /**
     * Gets the Y component of the glyph's advance.
     * 
     * @return the Y component of the glyph's advance.
     */
    public float getAdvanceY() {
        return this.advanceY;
    }

    /**
     * Gets the X component of the glyph's advance.
     * 
     * @return the X component of the glyph's advance.
     */
    public float getAdvanceX() {
        return this.advanceX;
    }

    /**
     * Gets the glyph's advance along the baseline.
     * 
     * @return the glyph's advance.
     */
    public float getAdvance() {
        if (this.horizontal) {
            return this.advanceX;
        }
        return this.advanceY;
    }

}

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

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphJustificationInfo;
import java.awt.font.GlyphMetrics;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * The GlyphVector class contains a collection of glyphs with geometric
 * information and each glyph's location. Each GlyphVector can be associated
 * with only one Font. GlyphVector contains the following properties for each
 * glyph:
 * <ul>
 * <li>the glyph position;</li>
 * <li>the transform of the glyph;</li>
 * <li>the metrics of the glyph in the context of the GlyphVector.</li>
 * </ul>
 * 
 * @since Android 1.0
 */
public abstract class GlyphVector implements Cloneable {

    /**
     * The Constant FLAG_HAS_TRANSFORMS indicates that this GlyphVector has
     * per-glyph transforms.
     */
    public static final int FLAG_HAS_TRANSFORMS = 1;

    /**
     * The Constant FLAG_HAS_POSITION_ADJUSTMENTS indicates that the GlyphVector
     * has per-glyph position adjustments.
     */
    public static final int FLAG_HAS_POSITION_ADJUSTMENTS = 2;

    /**
     * The Constant FLAG_RUN_RTL indicates that this GlyphVector has a right to
     * left run direction.
     */
    public static final int FLAG_RUN_RTL = 4;

    /**
     * The Constant FLAG_COMPLEX_GLYPHS indicates that this GlyphVector has a
     * complex glyph to char mapping.
     */
    public static final int FLAG_COMPLEX_GLYPHS = 8;

    /**
     * The Constant FLAG_MASK indicates a mask for supported flags from
     * getLayoutFlags.
     */
    public static final int FLAG_MASK = 15; // (|) mask of other flags

    /**
     * Instantiates a new GlyphVector.
     */
    public GlyphVector() {
    }

    /**
     * Gets the pixel bounds of the GlyphVector when rendered at the specified
     * location with the specified FontRenderContext.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param x
     *            the X coordinate of the GlyphVector's location.
     * @param y
     *            the Y coordinate of the GlyphVector's location.
     * @return the pixel bounds
     */
    public Rectangle getPixelBounds(FontRenderContext frc, float x, float y) {
        // default implementation - integer Rectangle, that encloses visual
        // bounds rectangle
        Rectangle2D visualRect = getVisualBounds();

        int minX = (int)Math.floor(visualRect.getMinX() + x);
        int minY = (int)Math.floor(visualRect.getMinY() + y);
        int width = (int)Math.ceil(visualRect.getMaxX() + x) - minX;
        int height = (int)Math.ceil(visualRect.getMaxY() + y) - minY;

        return new Rectangle(minX, minY, width, height);
    }

    /**
     * Gets the pixel bounds of the glyph with the specified index in this
     * GlyphVector which is rendered with the specified FontRenderContext at the
     * specified location.
     * 
     * @param index
     *            the glyph index in this GlyphVector.
     * @param frc
     *            the FontRenderContext.
     * @param x
     *            the X coordinate of the GlyphVector's location.
     * @param y
     *            the Y coordinate of the GlyphVector's location.
     * @return a Rectangle bounds.
     */
    public Rectangle getGlyphPixelBounds(int index, FontRenderContext frc, float x, float y) {
        Rectangle2D visualRect = getGlyphVisualBounds(index).getBounds2D();

        int minX = (int)Math.floor(visualRect.getMinX() + x);
        int minY = (int)Math.floor(visualRect.getMinY() + y);
        int width = (int)Math.ceil(visualRect.getMaxX() + x) - minX;
        int height = (int)Math.ceil(visualRect.getMaxY() + y) - minY;

        return new Rectangle(minX, minY, width, height);
    }

    /**
     * Gets the visual bounds of the GlyphVector.
     * 
     * @return the visual bounds of the GlyphVector.
     */
    public abstract Rectangle2D getVisualBounds();

    /**
     * Gets the logical bounds of the GlyphVector.
     * 
     * @return the logical bounds of the GlyphVector.
     */
    public abstract Rectangle2D getLogicalBounds();

    /**
     * Sets the position of the specified glyph in this GlyphVector.
     * 
     * @param glyphIndex
     *            the glyph index in this GlyphVector.
     * @param newPos
     *            the new position of the glyph at the specified glyphIndex.
     */
    public abstract void setGlyphPosition(int glyphIndex, Point2D newPos);

    /**
     * Gets the position of the specified glyph in this GlyphVector.
     * 
     * @param glyphIndex
     *            the glyph index in this GlyphVector.
     * @return the position of the specified glyph in this GlyphVector.
     */
    public abstract Point2D getGlyphPosition(int glyphIndex);

    /**
     * Sets the affine transform to a glyph with the specified index in this
     * GlyphVector.
     * 
     * @param glyphIndex
     *            the glyth index in this GlyphVector.
     * @param trans
     *            the AffineTransform to be assigned to the specified glyph.
     */
    public abstract void setGlyphTransform(int glyphIndex, AffineTransform trans);

    /**
     * Gets the transform of the specified glyph in this GlyphVector.
     * 
     * @param glyphIndex
     *            the glyph index in this GlyphVector.
     * @return the new transform of the glyph.
     */
    public abstract AffineTransform getGlyphTransform(int glyphIndex);

    /**
     * Compares this GlyphVector with the specified GlyphVector objects.
     * 
     * @param glyphVector
     *            the GlyphVector object to be compared.
     * @return true, if this GlyphVector is equal to the specified GlyphVector
     *         object, false otherwise.
     */
    public abstract boolean equals(GlyphVector glyphVector);

    /**
     * Gets the metrics of the glyph with the specified index in this
     * GlyphVector.
     * 
     * @param glyphIndex
     *            index in this GlyphVector.
     * @return the metrics of the glyph with the specified index in this
     *         GlyphVector.
     */
    public abstract GlyphMetrics getGlyphMetrics(int glyphIndex);

    /**
     * Gets the justification information of the glyph whose index is specified.
     * 
     * @param glyphIndex
     *            the glyph index.
     * @return the GlyphJustificationInfo for the specified glyph.
     */
    public abstract GlyphJustificationInfo getGlyphJustificationInfo(int glyphIndex);

    /**
     * Gets the FontRenderContext of this GlyphVector.
     * 
     * @return the FontRenderContext of this GlyphVector.
     */
    public abstract FontRenderContext getFontRenderContext();

    /**
     * Gets a Shape object which defines the visual representation of the
     * specified glyph in this GlyphVector, translated a distance of x in the X
     * direction and y in the Y direction.
     * 
     * @param glyphIndex
     *            the glyth index in this GlyphVector.
     * @param x
     *            the distance in the X direction to translate the shape object
     *            before returning it.
     * @param y
     *            the distance in the Y direction to translate the shape object
     *            before returning it.
     * @return a Shape object which represents the visual representation of the
     *         specified glyph in this GlyphVector - glyph outline.
     */
    public Shape getGlyphOutline(int glyphIndex, float x, float y) {
        Shape initialShape = getGlyphOutline(glyphIndex);
        AffineTransform trans = AffineTransform.getTranslateInstance(x, y);
        return trans.createTransformedShape(initialShape);
    }

    /**
     * Gets the visual bounds of the specified glyph in the GlyphVector.
     * 
     * @param glyphIndex
     *            the glyph index in this GlyphVector.
     * @return the glyph visual bounds of the glyph with the specified index in
     *         the GlyphVector.
     */
    public abstract Shape getGlyphVisualBounds(int glyphIndex);

    /**
     * Gets a Shape object which defines the visual representation of the
     * specified glyph in this GlyphVector.
     * 
     * @param glyphIndex
     *            the glyth index in this GlyphVector.
     * @return a Shape object which represents the visual representation of the
     *         specified glyph in this GlyphVector - glyph outline.
     */
    public abstract Shape getGlyphOutline(int glyphIndex);

    /**
     * Gets the logical bounds of the specified glyph in the GlyphVector.
     * 
     * @param glyphIndex
     *            the index in this GlyphVector of the glyph from which to
     *            retrieve its logical bounds
     * @return the logical bounds of the specified glyph in the GlyphVector.
     */
    public abstract Shape getGlyphLogicalBounds(int glyphIndex);

    /**
     * Gets the visual representation of this GlyphVector rendered in x, y
     * location as a Shape object.
     * 
     * @param x
     *            the x coordinate of the GlyphVector.
     * @param y
     *            the y coordinate of the GlyphVector.
     * @return the visual representation of this GlyphVector as a Shape object.
     */
    public abstract Shape getOutline(float x, float y);

    /**
     * Gets the visual representation of this GlyphVector as a Shape object.
     * 
     * @return the visual representation of this GlyphVector as a Shape object.
     */
    public abstract Shape getOutline();

    /**
     * Gets the font of this GlyphVector.
     * 
     * @return the font of this GlyphVector.
     */
    public abstract Font getFont();

    /**
     * Gets an array of the glyph codes of the specified glyphs.
     * 
     * @param beginGlyphIndex
     *            the index into this GlyphVector at which to start retrieving
     *            glyph codes.
     * @param numEntries
     *            the number of glyph codes.
     * @param codeReturn
     *            the array into which the resulting glyphcodes will be written.
     * @return the array of the glyph codes.
     */
    public abstract int[] getGlyphCodes(int beginGlyphIndex, int numEntries, int[] codeReturn);

    /**
     * Gets an array of the character indices of the specified glyphs.
     * 
     * @param beginGlyphIndex
     *            the index of the first glyph to return information for.
     * @param numEntries
     *            the number of glyph indices to return.
     * @param codeReturn
     *            the array into which the resulting character indices will be
     *            written.
     * @return an array of character indices for the specifies glyphs.
     */
    public int[] getGlyphCharIndices(int beginGlyphIndex, int numEntries, int[] codeReturn) {
        if (codeReturn == null) {
            codeReturn = new int[numEntries];
        }

        for (int i = 0; i < numEntries; i++) {
            codeReturn[i] = getGlyphCharIndex(i + beginGlyphIndex);
        }
        return codeReturn;
    }

    /**
     * Gets an array of the positions of the specified glyphs in this
     * GlyphVector.
     * 
     * @param beginGlyphIndex
     *            the index of the first glyph to return information for.
     * @param numEntries
     *            the number of glyphs to return information for.
     * @param positionReturn
     *            the array where the result will be stored.
     * @return an array of glyph positions.
     */
    public abstract float[] getGlyphPositions(int beginGlyphIndex, int numEntries,
            float[] positionReturn);

    /**
     * Gets the glyph code of the specified glyph.
     * 
     * @param glyphIndex
     *            the index in this GlyphVector which corresponds to the glyph
     *            from which to retrieve the glyphcode.
     * @return the glyphcode of the specified glyph.
     */
    public abstract int getGlyphCode(int glyphIndex);

    /**
     * Gets the first logical character's index of the specified glyph.
     * 
     * @param glyphIndex
     *            the glyph index.
     * @return the the first logical character's index.
     */
    public int getGlyphCharIndex(int glyphIndex) {
        // default implemetation one-to-one
        return glyphIndex;
    }

    /**
     * Sets default layout to this GlyphVector.
     */
    public abstract void performDefaultLayout();

    /**
     * Gets the number of glyphs in the GlyphVector.
     * 
     * @return the number of glyphs in the GlyphVector.
     */
    public abstract int getNumGlyphs();

    /**
     * Gets flags which describe the global state of the GlyphVector. The
     * default implementation returns 0.
     * 
     * @return the layout flags
     */
    public int getLayoutFlags() {
        // default implementation - returned value is 0
        return 0;
    }

}

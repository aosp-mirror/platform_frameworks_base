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

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The GraphicAttribute abstract class provides an opportunity to insert
 * graphical elements in printed text.
 * 
 * @since Android 1.0
 */
public abstract class GraphicAttribute {

    /**
     * The Constant TOP_ALIGNMENT indicates using the top line to calculate
     * placement of graphics.
     */
    public static final int TOP_ALIGNMENT = -1;

    /**
     * The Constant BOTTOM_ALIGNMENT indicates using the bottom line to
     * calculate placement of graphics.
     */
    public static final int BOTTOM_ALIGNMENT = -2;

    /**
     * The Constant ROMAN_BASELINE indicates the placement of the roman baseline
     * with respect to the graphics origin.
     */
    public static final int ROMAN_BASELINE = 0;

    /**
     * The Constant CENTER_BASELINE indicates the placement of the center
     * baseline with respect to the graphics origin.
     */
    public static final int CENTER_BASELINE = 1;

    /**
     * The Constant HANGING_BASELINE indicates the placement of the hanging
     * baseline with respect to the graphics origin.
     */
    public static final int HANGING_BASELINE = 2;

    // the alignment of this GraphicAttribute
    /**
     * The alignment.
     */
    private int alignment;

    /**
     * Instantiates a new graphic attribute with the specified alignment.
     * 
     * @param align
     *            the specified alignment.
     */
    protected GraphicAttribute(int align) {
        if ((align < BOTTOM_ALIGNMENT) || (align > HANGING_BASELINE)) {
            // awt.198=Illegal alignment argument
            throw new IllegalArgumentException(Messages.getString("awt.198")); //$NON-NLS-1$
        }
        this.alignment = align;
    }

    /**
     * Draws the GraphicAttribute at the specified location.
     * 
     * @param graphics
     *            the Graphics.
     * @param x
     *            the X coordinate of GraphicAttribute location.
     * @param y
     *            the Y coordinate of GraphicAttribute location.
     */
    public abstract void draw(Graphics2D graphics, float x, float y);

    /**
     * Gets the GraphicAttribute's advance. It's the distance from the point at
     * which the graphic is rendered and the point where the next character or
     * graphic is rendered.
     * 
     * @return the GraphicAttribute's advance.
     */
    public abstract float getAdvance();

    /**
     * Gets the alignment of this GraphicAttribute.
     * 
     * @return the alignment of this GraphicAttribute.
     */
    public final int getAlignment() {
        return this.alignment;
    }

    /**
     * Gets the ascent of this GraphicAttribute.
     * 
     * @return the ascent of this GraphicAttribute.
     */
    public abstract float getAscent();

    /**
     * Gets the bounds of this GraphicAttribute.
     * 
     * @return the bounds of this GraphicAttribute.
     */
    public Rectangle2D getBounds() {
        float ascent = getAscent();
        float advance = getAdvance();
        float descent = getDescent();

        // Default implementation - see API documentation.
        return new Rectangle2D.Float(0, -ascent, advance, ascent + descent);
    }

    /**
     * Gets the descent of this GraphicAttribute.
     * 
     * @return the descent of this GraphicAttribute.
     */
    public abstract float getDescent();

    /**
     * Gets the GlyphJustificationInfo of this GraphicAttribute.
     * 
     * @return the GlyphJustificationInfo of this GraphicAttribute.
     */
    public GlyphJustificationInfo getJustificationInfo() {

        /*
         * Default implementation. Since documentation doesn't describe default
         * values, they were calculated based on 1.5 release behavior and can be
         * obtained using next test sample: // Create GraphicAttribute class
         * implementation public class MyGraphicAttribute extends
         * GraphicAttribute { protected MyGraphicAttribute(int align) {
         * super(align); } public float getDescent() { return 0; } public float
         * getAdvance() { return 1; } public void draw(Graphics2D g2, float x,
         * float y) { } public float getAscent() { return 0; } }
         * MyGraphicAttribute myGA = gat.new MyGraphicAttribute(0); // print
         * justification parameters
         * System.out.println(myGA.getJustificationInfo().growAbsorb);
         * System.out.println(myGA.getJustificationInfo().shrinkAbsorb);
         * System.out.println(myGA.getJustificationInfo().growLeftLimit);
         * System.out.println(myGA.getJustificationInfo().growPriority);
         * System.out.println(myGA.getJustificationInfo().growRightLimit);
         * System.out.println(myGA.getJustificationInfo().shrinkLeftLimit);
         * System.out.println(myGA.getJustificationInfo().shrinkPriority);
         * System.out.println(myGA.getJustificationInfo().shrinkRightLimit);
         * System.out.println(myGA.getJustificationInfo().weight);
         */
        float advance = getAdvance();
        return new GlyphJustificationInfo(advance, false,
                GlyphJustificationInfo.PRIORITY_INTERCHAR, advance / 3, advance / 3, false,
                GlyphJustificationInfo.PRIORITY_WHITESPACE, 0, 0);
    }

}

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

package java.awt;

import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;
import java.text.CharacterIterator;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The FontMetrics class contains information about the rendering of a
 * particular font on a particular screen.
 * <p>
 * Each character in the Font has three values that help define where to place
 * it: an ascent, a descent, and an advance. The ascent is the distance the
 * character extends above the baseline. The descent is the distance the
 * character extends below the baseline. The advance width defines the position
 * at which the next character should be placed.
 * <p>
 * An array of characters or a string has an ascent, a descent, and an advance
 * width too. The ascent or descent of the array is specified by the maximum
 * ascent or descent of the characters in the array. The advance width is the
 * sum of the advance widths of each of the characters in the character array.
 * </p>
 * 
 * @since Android 1.0
 */
public abstract class FontMetrics implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 1681126225205050147L;

    /**
     * The font from which the FontMetrics is created.
     */
    protected Font font;

    /**
     * Instantiates a new font metrics from the specified Font.
     * 
     * @param fnt
     *            the Font.
     */
    protected FontMetrics(Font fnt) {
        this.font = fnt;
    }

    /**
     * Returns the String representation of this FontMetrics.
     * 
     * @return the string.
     */
    @Override
    public String toString() {
        return this.getClass().getName() + "[font=" + this.getFont() + //$NON-NLS-1$
                "ascent=" + this.getAscent() + //$NON-NLS-1$
                ", descent=" + this.getDescent() + //$NON-NLS-1$
                ", height=" + this.getHeight() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Gets the font associated with this FontMetrics.
     * 
     * @return the font associated with this FontMetrics.
     */
    public Font getFont() {
        return font;
    }

    /**
     * Gets the height of the text line in this Font.
     * 
     * @return the height of the text line in this Font.
     */
    public int getHeight() {
        return this.getAscent() + this.getDescent() + this.getLeading();
    }

    /**
     * Gets the font ascent of the Font associated with this FontMetrics. The
     * font ascent is the distance from the font's baseline to the top of most
     * alphanumeric characters.
     * 
     * @return the ascent of the Font associated with this FontMetrics.
     */
    public int getAscent() {
        return 0;
    }

    /**
     * Gets the font descent of the Font associated with this FontMetrics. The
     * font descent is the distance from the font's baseline to the bottom of
     * most alphanumeric characters with descenders.
     * 
     * @return the descent of the Font associated with this FontMetrics.
     */
    public int getDescent() {
        return 0;
    }

    /**
     * Gets the leading of the Font associated with this FontMetrics.
     * 
     * @return the leading of the Font associated with this FontMetrics.
     */
    public int getLeading() {
        return 0;
    }

    /**
     * Gets the LineMetrics object for the specified CharacterIterator in the
     * specified Graphics.
     * 
     * @param ci
     *            the CharacterIterator.
     * @param beginIndex
     *            the offset.
     * @param limit
     *            the number of characters to be used.
     * @param context
     *            the Graphics.
     * @return the LineMetrics object for the specified CharacterIterator in the
     *         specified Graphics.
     */
    public LineMetrics getLineMetrics(CharacterIterator ci, int beginIndex, int limit,
            Graphics context) {
        return font.getLineMetrics(ci, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the LineMetrics object for the specified String in the specified
     * Graphics.
     * 
     * @param str
     *            the String.
     * @param context
     *            the Graphics.
     * @return the LineMetrics object for the specified String in the specified
     *         Graphics.
     */
    public LineMetrics getLineMetrics(String str, Graphics context) {
        return font.getLineMetrics(str, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the LineMetrics object for the specified character array in the
     * specified Graphics.
     * 
     * @param chars
     *            the character array.
     * @param beginIndex
     *            the offset of array.
     * @param limit
     *            the number of characters to be used.
     * @param context
     *            the Graphics.
     * @return the LineMetrics object for the specified character array in the
     *         specified Graphics.
     */
    public LineMetrics getLineMetrics(char[] chars, int beginIndex, int limit, Graphics context) {
        return font.getLineMetrics(chars, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the LineMetrics object for the specified String in the specified
     * Graphics.
     * 
     * @param str
     *            the String.
     * @param beginIndex
     *            the offset.
     * @param limit
     *            the number of characters to be used.
     * @param context
     *            the Graphics.
     * @return the LineMetrics object for the specified String in the specified
     *         Graphics.
     */
    public LineMetrics getLineMetrics(String str, int beginIndex, int limit, Graphics context) {
        return font.getLineMetrics(str, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Returns the character's maximum bounds in the specified Graphics context.
     * 
     * @param context
     *            the Graphics context.
     * @return the character's maximum bounds in the specified Graphics context.
     */
    public Rectangle2D getMaxCharBounds(Graphics context) {
        return this.font.getMaxCharBounds(this.getFRCFromGraphics(context));
    }

    /**
     * Gets the bounds of the specified CharacterIterator in the specified
     * Graphics context.
     * 
     * @param ci
     *            the CharacterIterator.
     * @param beginIndex
     *            the begin offset of the array.
     * @param limit
     *            the number of characters.
     * @param context
     *            the Graphics.
     * @return the bounds of the specified CharacterIterator in the specified
     *         Graphics context.
     */
    public Rectangle2D getStringBounds(CharacterIterator ci, int beginIndex, int limit,
            Graphics context) {
        return font.getStringBounds(ci, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the bounds of the specified String in the specified Graphics
     * context.
     * 
     * @param str
     *            the String.
     * @param beginIndex
     *            the begin offset of the array.
     * @param limit
     *            the number of characters.
     * @param context
     *            the Graphics.
     * @return the bounds of the specified String in the specified Graphics
     *         context.
     */
    public Rectangle2D getStringBounds(String str, int beginIndex, int limit, Graphics context) {
        return font.getStringBounds(str, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the bounds of the specified characters array in the specified
     * Graphics context.
     * 
     * @param chars
     *            the characters array.
     * @param beginIndex
     *            the begin offset of the array.
     * @param limit
     *            the number of characters.
     * @param context
     *            the Graphics.
     * @return the bounds of the specified characters array in the specified
     *         Graphics context.
     */
    public Rectangle2D getStringBounds(char[] chars, int beginIndex, int limit, Graphics context) {
        return font.getStringBounds(chars, beginIndex, limit, this.getFRCFromGraphics(context));
    }

    /**
     * Gets the bounds of the specified String in the specified Graphics
     * context.
     * 
     * @param str
     *            the String.
     * @param context
     *            the Graphics.
     * @return the bounds of the specified String in the specified Graphics
     *         context.
     */
    public Rectangle2D getStringBounds(String str, Graphics context) {
        return font.getStringBounds(str, this.getFRCFromGraphics(context));
    }

    /**
     * Checks if the Font has uniform line metrics or not. The Font can contain
     * characters of other fonts for covering character set. In this case the
     * Font isn't uniform.
     * 
     * @return true, if the Font has uniform line metrics, false otherwise.
     */
    public boolean hasUniformLineMetrics() {
        return this.font.hasUniformLineMetrics();
    }

    /**
     * Returns the distance from the leftmost point to the rightmost point on
     * the string's baseline showing the specified array of bytes in this Font.
     * 
     * @param data
     *            the array of bytes to be measured.
     * @param off
     *            the start offset.
     * @param len
     *            the number of bytes to be measured.
     * @return the advance width of the array.
     */
    public int bytesWidth(byte[] data, int off, int len) {
        int width = 0;
        if ((off >= data.length) || (off < 0)) {
            // awt.13B=offset off is out of range
            throw new IllegalArgumentException(Messages.getString("awt.13B")); //$NON-NLS-1$
        }

        if ((off + len > data.length)) {
            // awt.13C=number of elemets len is out of range
            throw new IllegalArgumentException(Messages.getString("awt.13C")); //$NON-NLS-1$
        }

        for (int i = off; i < off + len; i++) {
            width += charWidth(data[i]);
        }

        return width;
    }

    /**
     * Returns the distance from the leftmost point to the rightmost point on
     * the string's baseline showing the specified array of characters in this
     * Font.
     * 
     * @param data
     *            the array of characters to be measured.
     * @param off
     *            the start offset.
     * @param len
     *            the number of bytes to be measured.
     * @return the advance width of the array.
     */
    public int charsWidth(char[] data, int off, int len) {
        int width = 0;
        if ((off >= data.length) || (off < 0)) {
            // awt.13B=offset off is out of range
            throw new IllegalArgumentException(Messages.getString("awt.13B")); //$NON-NLS-1$
        }

        if ((off + len > data.length)) {
            // awt.13C=number of elemets len is out of range
            throw new IllegalArgumentException(Messages.getString("awt.13C")); //$NON-NLS-1$
        }

        for (int i = off; i < off + len; i++) {
            width += charWidth(data[i]);
        }

        return width;
    }

    /**
     * Returns the distance from the leftmost point to the rightmost point of
     * the specified character in this Font.
     * 
     * @param ch
     *            the specified Unicode point code of character to be measured.
     * @return the advance width of the character.
     */
    public int charWidth(int ch) {
        return 0;
    }

    /**
     * Returns the distance from the leftmost point to the rightmost point of
     * the specified character in this Font.
     * 
     * @param ch
     *            the specified character to be measured.
     * @return the advance width of the character.
     */
    public int charWidth(char ch) {
        return 0;
    }

    /**
     * Gets the maximum advance width of character in this Font.
     * 
     * @return the maximum advance width of character in this Font.
     */
    public int getMaxAdvance() {
        return 0;
    }

    /**
     * Gets the maximum font ascent of the Font associated with this
     * FontMetrics.
     * 
     * @return the maximum font ascent of the Font associated with this
     *         FontMetrics.
     */
    public int getMaxAscent() {
        return 0;
    }

    /**
     * Gets the maximum font descent of character in this Font.
     * 
     * @return the maximum font descent of character in this Font.
     * @deprecated Replaced by getMaxDescent() method.
     */
    @Deprecated
    public int getMaxDecent() {
        return 0;
    }

    /**
     * Gets the maximum font descent of character in this Font.
     * 
     * @return the maximum font descent of character in this Font.
     */
    public int getMaxDescent() {
        return 0;
    }

    /**
     * Gets the advance widths of the characters in the Font.
     * 
     * @return the advance widths of the characters in the Font.
     */
    public int[] getWidths() {
        return null;
    }

    /**
     * Returns the advance width for the specified String in this Font.
     * 
     * @param str
     *            String to be measured.
     * @return the the advance width for the specified String in this Font.
     */
    public int stringWidth(String str) {
        return 0;
    }

    /**
     * Returns a FontRenderContext instance of the Graphics context specified.
     * 
     * @param context
     *            the specified Graphics context.
     * @return a FontRenderContext of the specified Graphics context.
     */
    private FontRenderContext getFRCFromGraphics(Graphics context) {
        FontRenderContext frc;
        if (context instanceof Graphics2D) {
            frc = ((Graphics2D)context).getFontRenderContext();
        } else {
            frc = new FontRenderContext(null, false, false);
        }

        return frc;
    }
}

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
 */

package java.awt.font;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.GeneralPath;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.Map;

import org.apache.harmony.awt.gl.font.BasicMetrics;
import org.apache.harmony.awt.gl.font.CaretManager;
import org.apache.harmony.awt.gl.font.TextMetricsCalculator;
import org.apache.harmony.awt.gl.font.TextRunBreaker;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The TextLayout class defines the graphical representation of character data.
 * This class provides method for obtaining information about cursor positioning
 * and movement, split cursors for text with different directions, logical and
 * visual highlighting, multiple baselines, hits, justification, ascent,
 * descent, and advance, and rendering. A TextLayout object can be rendered
 * using Graphics context.
 * 
 * @since Android 1.0
 */
public final class TextLayout implements Cloneable {

    /**
     * The CaretPolicy class provides a policy for obtaining the caret location.
     * The single getStrongCaret method specifies the policy.
     */
    public static class CaretPolicy {

        /**
         * Instantiates a new CaretPolicy.
         */
        public CaretPolicy() {
            // Nothing to do
        }

        /**
         * Returns whichever of the two specified TextHitInfo objects has the
         * stronger caret (higher character level) in the specified TextLayout.
         * 
         * @param hit1
         *            the first TextHitInfo of the specified TextLayout.
         * @param hit2
         *            the second TextHitInfo of the specified TextLayout.
         * @param layout
         *            the TextLayout.
         * @return the TextHitInfo with the stronger caret.
         */
        public TextHitInfo getStrongCaret(TextHitInfo hit1, TextHitInfo hit2, TextLayout layout) {
            // Stronger hit is the one with greater level.
            // If the level is same, leading edge is stronger.

            int level1 = layout.getCharacterLevel(hit1.getCharIndex());
            int level2 = layout.getCharacterLevel(hit2.getCharIndex());

            if (level1 == level2) {
                return (hit2.isLeadingEdge() && (!hit1.isLeadingEdge())) ? hit2 : hit1;
            }
            return level1 > level2 ? hit1 : hit2;
        }

    }

    /**
     * The Constant DEFAULT_CARET_POLICY indicates the default caret policy.
     */
    public static final TextLayout.CaretPolicy DEFAULT_CARET_POLICY = new CaretPolicy();

    /**
     * The breaker.
     */
    private TextRunBreaker breaker;

    /**
     * The metrics valid.
     */
    private boolean metricsValid = false;

    /**
     * The tmc.
     */
    private TextMetricsCalculator tmc;

    /**
     * The metrics.
     */
    private BasicMetrics metrics;

    /**
     * The caret manager.
     */
    private CaretManager caretManager;

    /**
     * The justification width.
     */
    float justificationWidth = -1;

    /**
     * Instantiates a new TextLayout object from the specified string and Font.
     * 
     * @param string
     *            the string to be displayed.
     * @param font
     *            the font of the text.
     * @param frc
     *            the FontRenderContext object for obtaining information about a
     *            graphics device.
     */
    public TextLayout(String string, Font font, FontRenderContext frc) {
        if (string == null) {
            // awt.01='{0}' parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.01", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (font == null) {
            // awt.01='{0}' parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.01", "font")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (string.length() == 0) {
            // awt.02='{0}' parameter has zero length
            throw new IllegalArgumentException(Messages.getString("awt.02", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        AttributedString as = new AttributedString(string);
        as.addAttribute(TextAttribute.FONT, font);
        this.breaker = new TextRunBreaker(as.getIterator(), frc);
        caretManager = new CaretManager(breaker);
    }

    /**
     * Instantiates a new TextLayout from the specified text and a map of
     * attributes.
     * 
     * @param string
     *            the string to be displayed.
     * @param attributes
     *            the attributes to be used for obtaining the text style.
     * @param frc
     *            the FontRenderContext object for obtaining information about a
     *            graphics device.
     */
    public TextLayout(String string,
            Map<? extends java.text.AttributedCharacterIterator.Attribute, ?> attributes,
            FontRenderContext frc) {
        if (string == null) {
            // awt.01='{0}' parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.01", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (attributes == null) {
            // awt.01='{0}' parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.01", "attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (string.length() == 0) {
            // awt.02='{0}' parameter has zero length
            throw new IllegalArgumentException(Messages.getString("awt.02", "string")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        AttributedString as = new AttributedString(string);
        as.addAttributes(attributes, 0, string.length());
        this.breaker = new TextRunBreaker(as.getIterator(), frc);
        caretManager = new CaretManager(breaker);
    }

    /**
     * Instantiates a new TextLayout from the AttributedCharacterIterator.
     * 
     * @param text
     *            the AttributedCharacterIterator.
     * @param frc
     *            the FontRenderContext object for obtaining information about a
     *            graphics device.
     */
    public TextLayout(AttributedCharacterIterator text, FontRenderContext frc) {
        if (text == null) {
            // awt.03='{0}' iterator parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.03", "text")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (text.getBeginIndex() == text.getEndIndex()) {
            // awt.04='{0}' iterator parameter has zero length
            throw new IllegalArgumentException(Messages.getString("awt.04", "text")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        this.breaker = new TextRunBreaker(text, frc);
        caretManager = new CaretManager(breaker);
    }

    /**
     * Instantiates a new text layout.
     * 
     * @param breaker
     *            the breaker.
     */
    TextLayout(TextRunBreaker breaker) {
        this.breaker = breaker;
        caretManager = new CaretManager(this.breaker);
    }

    /**
     * Returns a hash code of this TextLayout object.
     * 
     * @return a hash code of this TextLayout object.
     */
    @Override
    public int hashCode() {
        return breaker.hashCode();
    }

    /**
     * Returns a copy of this object.
     * 
     * @return a copy of this object.
     */
    @Override
    protected Object clone() {
        TextLayout res = new TextLayout((TextRunBreaker)breaker.clone());

        if (justificationWidth >= 0) {
            res.handleJustify(justificationWidth);
        }

        return res;
    }

    /**
     * Compares this TextLayout object to the specified TextLayout object.
     * 
     * @param layout
     *            the TextLayout object to be compared.
     * @return true, if this TextLayout object is equal to the specified
     *         TextLayout object, false otherwise.
     */
    public boolean equals(TextLayout layout) {
        if (layout == null) {
            return false;
        }
        return this.breaker.equals(layout.breaker);
    }

    /**
     * Compares this TextLayout object to the specified Object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if this TextLayout object is equal to the specified Object,
     *         false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof TextLayout ? equals((TextLayout)obj) : false;
    }

    /**
     * Gets the string representation for this TextLayout.
     * 
     * @return the string representation for this TextLayout.
     */
    @Override
    public String toString() { // what for?
        return super.toString();
    }

    /**
     * Draws this TextLayout at the specified location with the specified
     * Graphics2D context.
     * 
     * @param g2d
     *            the Graphics2D object which renders this TextLayout.
     * @param x
     *            the X coordinate of the TextLayout origin.
     * @param y
     *            the Y coordinate of the TextLayout origin.
     */
    public void draw(Graphics2D g2d, float x, float y) {
        updateMetrics();
        breaker.drawSegments(g2d, x, y);
    }

    /**
     * Update metrics.
     */
    private void updateMetrics() {
        if (!metricsValid) {
            breaker.createAllSegments();
            tmc = new TextMetricsCalculator(breaker);
            metrics = tmc.createMetrics();
            metricsValid = true;
        }
    }

    /**
     * Gets the advance of this TextLayout object.
     * 
     * @return the advance of this TextLayout object.
     */
    public float getAdvance() {
        updateMetrics();
        return metrics.getAdvance();
    }

    /**
     * Gets the ascent of this TextLayout object.
     * 
     * @return the ascent of this TextLayout object.
     */
    public float getAscent() {
        updateMetrics();
        return metrics.getAscent();
    }

    /**
     * Gets the baseline of this TextLayout object.
     * 
     * @return the baseline of this TextLayout object.
     */
    public byte getBaseline() {
        updateMetrics();
        return (byte)metrics.getBaseLineIndex();
    }

    /**
     * Gets the float array of offsets for the baselines which are used in this
     * TextLayout.
     * 
     * @return the float array of offsets for the baselines which are used in
     *         this TextLayout.
     */
    public float[] getBaselineOffsets() {
        updateMetrics();
        return tmc.getBaselineOffsets();
    }

    /**
     * Gets the black box bounds of the characters in the specified area. The
     * black box bounds is an Shape which contains all bounding boxes of all the
     * glyphs of the characters between firstEndpoint and secondEndpoint
     * parameters values.
     * 
     * @param firstEndpoint
     *            the first point of the area.
     * @param secondEndpoint
     *            the second point of the area.
     * @return the Shape which contains black box bounds.
     */
    public Shape getBlackBoxBounds(int firstEndpoint, int secondEndpoint) {
        updateMetrics();
        if (firstEndpoint < secondEndpoint) {
            return breaker.getBlackBoxBounds(firstEndpoint, secondEndpoint);
        }
        return breaker.getBlackBoxBounds(secondEndpoint, firstEndpoint);
    }

    /**
     * Gets the bounds of this TextLayout.
     * 
     * @return the bounds of this TextLayout.
     */
    public Rectangle2D getBounds() {
        updateMetrics();
        return breaker.getVisualBounds();
    }

    /**
     * Gets information about the caret of the specified TextHitInfo.
     * 
     * @param hitInfo
     *            the TextHitInfo.
     * @return the information about the caret of the specified TextHitInfo.
     */
    public float[] getCaretInfo(TextHitInfo hitInfo) {
        updateMetrics();
        return caretManager.getCaretInfo(hitInfo);
    }

    /**
     * Gets information about the caret of the specified TextHitInfo of a
     * character in this TextLayout.
     * 
     * @param hitInfo
     *            the TextHitInfo of a character in this TextLayout.
     * @param bounds
     *            the bounds to which the caret info is constructed.
     * @return the caret of the specified TextHitInfo.
     */
    public float[] getCaretInfo(TextHitInfo hitInfo, Rectangle2D bounds) {
        updateMetrics();
        return caretManager.getCaretInfo(hitInfo);
    }

    /**
     * Gets a Shape which represents the caret of the specified TextHitInfo in
     * the bounds of this TextLayout.
     * 
     * @param hitInfo
     *            the TextHitInfo.
     * @param bounds
     *            the bounds to which the caret info is constructed.
     * @return the Shape which represents the caret.
     */
    public Shape getCaretShape(TextHitInfo hitInfo, Rectangle2D bounds) {
        updateMetrics();
        return caretManager.getCaretShape(hitInfo, this);
    }

    /**
     * Gets a Shape which represents the caret of the specified TextHitInfo in
     * the bounds of this TextLayout.
     * 
     * @param hitInfo
     *            the TextHitInfo.
     * @return the Shape which represents the caret.
     */
    public Shape getCaretShape(TextHitInfo hitInfo) {
        updateMetrics();
        return caretManager.getCaretShape(hitInfo, this);
    }

    /**
     * Gets two Shapes for the strong and weak carets with default caret policy
     * and null bounds: the first element is the strong caret, the second is the
     * weak caret or null.
     * 
     * @param offset
     *            an offset in the TextLayout.
     * @return an array of two Shapes corresponded to the strong and weak
     *         carets.
     */
    public Shape[] getCaretShapes(int offset) {
        return getCaretShapes(offset, null, TextLayout.DEFAULT_CARET_POLICY);
    }

    /**
     * Gets two Shapes for the strong and weak carets with the default caret
     * policy: the first element is the strong caret, the second is the weak
     * caret or null.
     * 
     * @param offset
     *            an offset in the TextLayout.
     * @param bounds
     *            the bounds to which to extend the carets.
     * @return an array of two Shapes corresponded to the strong and weak
     *         carets.
     */
    public Shape[] getCaretShapes(int offset, Rectangle2D bounds) {
        return getCaretShapes(offset, bounds, TextLayout.DEFAULT_CARET_POLICY);
    }

    /**
     * Gets two Shapes for the strong and weak carets: the first element is the
     * strong caret, the second is the weak caret or null.
     * 
     * @param offset
     *            an offset in the TextLayout.
     * @param bounds
     *            the bounds to which to extend the carets.
     * @param policy
     *            the specified CaretPolicy.
     * @return an array of two Shapes corresponded to the strong and weak
     *         carets.
     */
    public Shape[] getCaretShapes(int offset, Rectangle2D bounds, TextLayout.CaretPolicy policy) {
        if (offset < 0 || offset > breaker.getCharCount()) {
            // awt.195=Offset is out of bounds
            throw new IllegalArgumentException(Messages.getString("awt.195")); //$NON-NLS-1$
        }

        updateMetrics();
        return caretManager.getCaretShapes(offset, bounds, policy, this);
    }

    /**
     * Gets the number of characters in this TextLayout.
     * 
     * @return the number of characters in this TextLayout.
     */
    public int getCharacterCount() {
        return breaker.getCharCount();
    }

    /**
     * Gets the level of the character with the specified index.
     * 
     * @param index
     *            the specified index of the character.
     * @return the level of the character.
     */
    public byte getCharacterLevel(int index) {
        if (index == -1 || index == getCharacterCount()) {
            return (byte)breaker.getBaseLevel();
        }
        return breaker.getLevel(index);
    }

    /**
     * Gets the descent of this TextLayout.
     * 
     * @return the descent of this TextLayout.
     */
    public float getDescent() {
        updateMetrics();
        return metrics.getDescent();
    }

    /**
     * Gets the TextLayout wich is justified with the specified width related to
     * this TextLayout.
     * 
     * @param justificationWidth
     *            the width which is used for justification.
     * @return a TextLayout justified to the specified width.
     * @throws Error
     *             the error occures if this TextLayout has been already
     *             justified.
     */
    public TextLayout getJustifiedLayout(float justificationWidth) throws Error {
        float justification = breaker.getJustification();

        if (justification < 0) {
            // awt.196=Justification impossible, layout already justified
            throw new Error(Messages.getString("awt.196")); //$NON-NLS-1$
        } else if (justification == 0) {
            return this;
        }

        TextLayout justifiedLayout = new TextLayout((TextRunBreaker)breaker.clone());
        justifiedLayout.handleJustify(justificationWidth);
        return justifiedLayout;
    }

    /**
     * Gets the leading of this TextLayout.
     * 
     * @return the leading of this TextLayout.
     */
    public float getLeading() {
        updateMetrics();
        return metrics.getLeading();
    }

    /**
     * Gets a Shape representing the logical selection betweeen the specified
     * endpoints and extended to the natural bounds of this TextLayout.
     * 
     * @param firstEndpoint
     *            the first selected endpoint within the area of characters
     * @param secondEndpoint
     *            the second selected endpoint within the area of characters
     * @return a Shape represented the logical selection betweeen the specified
     *         endpoints.
     */
    public Shape getLogicalHighlightShape(int firstEndpoint, int secondEndpoint) {
        updateMetrics();
        return getLogicalHighlightShape(firstEndpoint, secondEndpoint, breaker.getLogicalBounds());
    }

    /**
     * Gets a Shape representing the logical selection betweeen the specified
     * endpoints and extended to the specified bounds of this TextLayout.
     * 
     * @param firstEndpoint
     *            the first selected endpoint within the area of characters
     * @param secondEndpoint
     *            the second selected endpoint within the area of characters
     * @param bounds
     *            the specified bounds of this TextLayout.
     * @return a Shape represented the logical selection betweeen the specified
     *         endpoints.
     */
    public Shape getLogicalHighlightShape(int firstEndpoint, int secondEndpoint, Rectangle2D bounds) {
        updateMetrics();

        if (firstEndpoint > secondEndpoint) {
            if (secondEndpoint < 0 || firstEndpoint > breaker.getCharCount()) {
                // awt.197=Endpoints are out of range
                throw new IllegalArgumentException(Messages.getString("awt.197")); //$NON-NLS-1$
            }
            return caretManager.getLogicalHighlightShape(secondEndpoint, firstEndpoint, bounds,
                    this);
        }
        if (firstEndpoint < 0 || secondEndpoint > breaker.getCharCount()) {
            // awt.197=Endpoints are out of range
            throw new IllegalArgumentException(Messages.getString("awt.197")); //$NON-NLS-1$
        }
        return caretManager.getLogicalHighlightShape(firstEndpoint, secondEndpoint, bounds, this);
    }

    /**
     * Gets the logical ranges of text which corresponds to a visual selection.
     * 
     * @param hit1
     *            the first endpoint of the visual range.
     * @param hit2
     *            the second endpoint of the visual range.
     * @return the logical ranges of text which corresponds to a visual
     *         selection.
     */
    public int[] getLogicalRangesForVisualSelection(TextHitInfo hit1, TextHitInfo hit2) {
        return caretManager.getLogicalRangesForVisualSelection(hit1, hit2);
    }

    /**
     * Gets the TextHitInfo for the next caret to the left (or up at the end of
     * the line) of the specified offset.
     * 
     * @param offset
     *            the offset in this TextLayout.
     * @return the TextHitInfo for the next caret to the left (or up at the end
     *         of the line) of the specified hit, or null if there is no hit.
     */
    public TextHitInfo getNextLeftHit(int offset) {
        return getNextLeftHit(offset, DEFAULT_CARET_POLICY);
    }

    /**
     * Gets the TextHitInfo for the next caret to the left (or up at the end of
     * the line) of the specified hit.
     * 
     * @param hitInfo
     *            the initial hit.
     * @return the TextHitInfo for the next caret to the left (or up at the end
     *         of the line) of the specified hit, or null if there is no hit.
     */
    public TextHitInfo getNextLeftHit(TextHitInfo hitInfo) {
        breaker.createAllSegments();
        return caretManager.getNextLeftHit(hitInfo);
    }

    /**
     * Gets the TextHitInfo for the next caret to the left (or up at the end of
     * the line) of the specified offset, given the specified caret policy.
     * 
     * @param offset
     *            the offset in this TextLayout.
     * @param policy
     *            the policy to be used for obtaining the strong caret.
     * @return the TextHitInfo for the next caret to the left of the specified
     *         offset, or null if there is no hit.
     */
    public TextHitInfo getNextLeftHit(int offset, TextLayout.CaretPolicy policy) {
        if (offset < 0 || offset > breaker.getCharCount()) {
            // awt.195=Offset is out of bounds
            throw new IllegalArgumentException(Messages.getString("awt.195")); //$NON-NLS-1$
        }

        TextHitInfo hit = TextHitInfo.afterOffset(offset);
        TextHitInfo strongHit = policy.getStrongCaret(hit, hit.getOtherHit(), this);
        TextHitInfo nextLeftHit = getNextLeftHit(strongHit);

        if (nextLeftHit != null) {
            return policy.getStrongCaret(getVisualOtherHit(nextLeftHit), nextLeftHit, this);
        }
        return null;
    }

    /**
     * Gets the TextHitInfo for the next caret to the right (or down at the end
     * of the line) of the specified hit.
     * 
     * @param hitInfo
     *            the initial hit.
     * @return the TextHitInfo for the next caret to the right (or down at the
     *         end of the line) of the specified hit, or null if there is no
     *         hit.
     */
    public TextHitInfo getNextRightHit(TextHitInfo hitInfo) {
        breaker.createAllSegments();
        return caretManager.getNextRightHit(hitInfo);
    }

    /**
     * Gets the TextHitInfo for the next caret to the right (or down at the end
     * of the line) of the specified offset.
     * 
     * @param offset
     *            the offset in this TextLayout.
     * @return the TextHitInfo for the next caret to the right of the specified
     *         offset, or null if there is no hit.
     */
    public TextHitInfo getNextRightHit(int offset) {
        return getNextRightHit(offset, DEFAULT_CARET_POLICY);
    }

    /**
     * Gets the TextHitInfo for the next caret to the right (or down at the end
     * of the line) of the specified offset, given the specified caret policy.
     * 
     * @param offset
     *            the offset in this TextLayout.
     * @param policy
     *            the policy to be used for obtaining the strong caret.
     * @return the TextHitInfo for the next caret to the right of the specified
     *         offset, or null if there is no hit.
     */
    public TextHitInfo getNextRightHit(int offset, TextLayout.CaretPolicy policy) {
        if (offset < 0 || offset > breaker.getCharCount()) {
            // awt.195=Offset is out of bounds
            throw new IllegalArgumentException(Messages.getString("awt.195")); //$NON-NLS-1$
        }

        TextHitInfo hit = TextHitInfo.afterOffset(offset);
        TextHitInfo strongHit = policy.getStrongCaret(hit, hit.getOtherHit(), this);
        TextHitInfo nextRightHit = getNextRightHit(strongHit);

        if (nextRightHit != null) {
            return policy.getStrongCaret(getVisualOtherHit(nextRightHit), nextRightHit, this);
        }
        return null;
    }

    /**
     * Gets the outline of this TextLayout as a Shape.
     * 
     * @param xform
     *            the AffineTransform to be used to transform the outline before
     *            returning it, or null if no transformation is desired.
     * @return the outline of this TextLayout as a Shape.
     */
    public Shape getOutline(AffineTransform xform) {
        breaker.createAllSegments();

        GeneralPath outline = breaker.getOutline();

        if (outline != null && xform != null) {
            outline.transform(xform);
        }

        return outline;
    }

    /**
     * Gets the visible advance of this TextLayout which is defined as diffence
     * between leading (advance) and trailing whitespace.
     * 
     * @return the visible advance of this TextLayout.
     */
    public float getVisibleAdvance() {
        updateMetrics();

        // Trailing whitespace _SHOULD_ be reordered (Unicode spec) to
        // base direction, so it is also trailing
        // in logical representation. We use this fact.
        int lastNonWhitespace = breaker.getLastNonWhitespace();

        if (lastNonWhitespace < 0) {
            return 0;
        } else if (lastNonWhitespace == getCharacterCount() - 1) {
            return getAdvance();
        } else if (justificationWidth >= 0) { // Layout is justified
            return justificationWidth;
        } else {
            breaker.pushSegments(breaker.getACI().getBeginIndex(), lastNonWhitespace
                    + breaker.getACI().getBeginIndex() + 1);

            breaker.createAllSegments();

            float visAdvance = tmc.createMetrics().getAdvance();

            breaker.popSegments();
            return visAdvance;
        }
    }

    /**
     * Gets a Shape which corresponds to the highlighted (selected) area based
     * on two hit locations within the text and extends to the bounds.
     * 
     * @param hit1
     *            the first text hit location.
     * @param hit2
     *            the second text hit location.
     * @param bounds
     *            the rectangle that the highlighted area should be extended or
     *            restricted to.
     * @return a Shape which corresponds to the highlighted (selected) area.
     */
    public Shape getVisualHighlightShape(TextHitInfo hit1, TextHitInfo hit2, Rectangle2D bounds) {
        return caretManager.getVisualHighlightShape(hit1, hit2, bounds, this);
    }

    /**
     * Gets a Shape which corresponds to the highlighted (selected) area based
     * on two hit locations within the text.
     * 
     * @param hit1
     *            the first text hit location.
     * @param hit2
     *            the second text hit location.
     * @return a Shape which corresponds to the highlighted (selected) area.
     */
    public Shape getVisualHighlightShape(TextHitInfo hit1, TextHitInfo hit2) {
        breaker.createAllSegments();
        return caretManager.getVisualHighlightShape(hit1, hit2, breaker.getLogicalBounds(), this);
    }

    /**
     * Gets the TextHitInfo for a hit on the opposite side of the specified
     * hit's caret.
     * 
     * @param hitInfo
     *            the specified TextHitInfo.
     * @return the TextHitInfo for a hit on the opposite side of the specified
     *         hit's caret.
     */
    public TextHitInfo getVisualOtherHit(TextHitInfo hitInfo) {
        return caretManager.getVisualOtherHit(hitInfo);
    }

    /**
     * Justifies the text; this method should be overridden by subclasses.
     * 
     * @param justificationWidth
     *            the width for justification.
     */
    protected void handleJustify(float justificationWidth) {
        float justification = breaker.getJustification();

        if (justification < 0) {
            // awt.196=Justification impossible, layout already justified
            throw new IllegalStateException(Messages.getString("awt.196")); //$NON-NLS-1$
        } else if (justification == 0) {
            return;
        }

        float gap = (justificationWidth - getVisibleAdvance()) * justification;
        breaker.justify(gap);
        this.justificationWidth = justificationWidth;

        // Correct metrics
        tmc = new TextMetricsCalculator(breaker);
        tmc.correctAdvance(metrics);
    }

    /**
     * Returns a TextHitInfo object that gives information on which division
     * point (between two characters) is corresponds to a hit (such as a mouse
     * click) at the specified coordinates.
     * 
     * @param x
     *            the X coordinate in this TextLayout.
     * @param y
     *            the Y coordinate in this TextLayout. TextHitInfo object
     *            corresponding to the given coordinates within the text.
     * @return the information about the character at the specified position.
     */
    public TextHitInfo hitTestChar(float x, float y) {
        return hitTestChar(x, y, getBounds());
    }

    /**
     * Returns a TextHitInfo object that gives information on which division
     * point (between two characters) is corresponds to a hit (such as a mouse
     * click) at the specified coordinates within the specified text rectangle.
     * 
     * @param x
     *            the X coordinate in this TextLayout.
     * @param y
     *            the Y coordinate in this TextLayout.
     * @param bounds
     *            the bounds of the text area. TextHitInfo object corresponding
     *            to the given coordinates within the text.
     * @return the information about the character at the specified position.
     */
    public TextHitInfo hitTestChar(float x, float y, Rectangle2D bounds) {
        if (x > bounds.getMaxX()) {
            return breaker.isLTR() ? TextHitInfo.trailing(breaker.getCharCount() - 1) : TextHitInfo
                    .leading(0);
        }

        if (x < bounds.getMinX()) {
            return breaker.isLTR() ? TextHitInfo.leading(0) : TextHitInfo.trailing(breaker
                    .getCharCount() - 1);
        }

        return breaker.hitTest(x, y);
    }

    /**
     * Returns true if this TextLayout has a "left to right" direction.
     * 
     * @return true if this TextLayout has a "left to right" direction, false if
     *         this TextLayout has a "right to left" direction.
     */
    public boolean isLeftToRight() {
        return breaker.isLTR();
    }

    /**
     * Returns true if this TextLayout is vertical, false otherwise.
     * 
     * @return true if this TextLayout is vertical, false if horizontal.
     */
    public boolean isVertical() {
        return false;
    }
}

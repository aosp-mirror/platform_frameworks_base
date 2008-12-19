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

package java.awt;

import java.awt.font.GlyphVector;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * The Graphics2D class extends Graphics class and provides more capabilities
 * for rendering text, images, shapes. This provides methods to perform
 * transformation of coordinate system, color management, and text layout. The
 * following attributes exist for rendering:
 * <ul>
 * <li>Color - current Graphics2D color;</li>
 * <li>Font - current Graphics2D font;</li>
 * <li>Stroke - pen with a width of 1 pixel;</li>
 * <li>Transform - current Graphics2D Transformation;</li>
 * <li>Composite - alpha compositing rules for combining source and destination
 * colors.</li>
 * </ul>
 * 
 * @since Android 1.0
 */
public abstract class Graphics2D extends Graphics {

    /**
     * Instantiates a new Graphics2D object. This constructor should never be
     * called directly.
     */
    protected Graphics2D() {
        super();
    }

    /**
     * Adds preferences for the rendering algorithms. The preferences are
     * arbitrary and specified by Map objects. All specified by Map object
     * preferences can be modified.
     * 
     * @param hints
     *            the rendering hints.
     */
    public abstract void addRenderingHints(Map<?, ?> hints);

    /**
     * Intersects the current clipping area with the specified Shape and the
     * result becomes a new clipping area. If current clipping area is not
     * defined, the Shape becomes the new clipping area. No rendering operations
     * are allowed outside the clipping area.
     * 
     * @param s
     *            the specified Shape object which will be intersected with
     *            current clipping area.
     */
    public abstract void clip(Shape s);

    /**
     * Draws the outline of the specified Shape.
     * 
     * @param s
     *            the Shape which outline is drawn.
     */
    public abstract void draw(Shape s);

    /**
     * Draws the specified GlyphVector object's text at the point x, y.
     * 
     * @param g
     *            the GlyphVector object to be drawn.
     * @param x
     *            the X position where the GlyphVector's text should be
     *            rendered.
     * @param y
     *            the Y position where the GlyphVector's text should be
     *            rendered.
     */
    public abstract void drawGlyphVector(GlyphVector g, float x, float y);

    /**
     * Draws the BufferedImage -- modified according to the operation
     * BufferedImageOp -- at the point x, y.
     * 
     * @param img
     *            the BufferedImage to be rendered.
     * @param op
     *            the filter to be applied to the image before rendering.
     * @param x
     *            the X coordinate of the point where the image's upper left
     *            corner will be placed.
     * @param y
     *            the Y coordinate of the point where the image's upper left
     *            corner will be placed.
     */
    public abstract void drawImage(BufferedImage img, BufferedImageOp op, int x, int y);

    /**
     * Draws BufferedImage transformed from image space into user space
     * according to the AffineTransform xform and notifies the ImageObserver.
     * 
     * @param img
     *            the BufferedImage to be rendered.
     * @param xform
     *            the affine transformation from the image to the user space.
     * @param obs
     *            the ImageObserver to be notified about the image conversion.
     * @return true, if the image is successfully loaded and rendered, or it's
     *         null, otherwise false.
     */
    public abstract boolean drawImage(Image img, AffineTransform xform, ImageObserver obs);

    /**
     * Draws a RenderableImage which is transformed from image space into user
     * according to the AffineTransform xform.
     * 
     * @param img
     *            the RenderableImage to be rendered.
     * @param xform
     *            the affine transformation from image to user space.
     */
    public abstract void drawRenderableImage(RenderableImage img, AffineTransform xform);

    /**
     * Draws a RenderedImage which is transformed from image space into user
     * according to the AffineTransform xform.
     * 
     * @param img
     *            the RenderedImage to be rendered.
     * @param xform
     *            the affine transformation from image to user space.
     */
    public abstract void drawRenderedImage(RenderedImage img, AffineTransform xform);

    /**
     * Draws the string specified by the AttributedCharacterIterator. The first
     * character's position is specified by the X, Y parameters.
     * 
     * @param iterator
     *            whose text is drawn.
     * @param x
     *            the X position where the first character is drawn.
     * @param y
     *            the Y position where the first character is drawn.
     */
    public abstract void drawString(AttributedCharacterIterator iterator, float x, float y);

    /**
     * Draws the string specified by the AttributedCharacterIterator. The first
     * character's position is specified by the X, Y parameters.
     * 
     * @param iterator
     *            whose text is drawn.
     * @param x
     *            the X position where the first character is drawn.
     * @param y
     *            the Y position where the first character is drawn.
     * @see java.awt.Graphics#drawString(AttributedCharacterIterator, int, int)
     */
    @Override
    public abstract void drawString(AttributedCharacterIterator iterator, int x, int y);

    /**
     * Draws the String whose the first character position is specified by the
     * parameters X, Y.
     * 
     * @param s
     *            the String to be drawn.
     * @param x
     *            the X position of the first character.
     * @param y
     *            the Y position of the first character.
     */
    public abstract void drawString(String s, float x, float y);

    /**
     * Draws the String whose the first character coordinates are specified by
     * the parameters X, Y.
     * 
     * @param str
     *            the String to be drawn.
     * @param x
     *            the X coordinate of the first character.
     * @param y
     *            the Y coordinate of the first character.
     * @see java.awt.Graphics#drawString(String, int, int)
     */
    @Override
    public abstract void drawString(String str, int x, int y);

    /**
     * Fills the interior of the specified Shape.
     * 
     * @param s
     *            the Shape to be filled.
     */
    public abstract void fill(Shape s);

    /**
     * Gets the background color.
     * 
     * @return the current background color.
     */
    public abstract Color getBackground();

    /**
     * Gets the current composite of the Graphics2D.
     * 
     * @return the current composite which specifies the compositing style.
     */
    public abstract Composite getComposite();

    /**
     * Gets the device configuration.
     * 
     * @return the device configuration.
     */
    public abstract GraphicsConfiguration getDeviceConfiguration();

    /**
     * Gets the rendering context of the Font.
     * 
     * @return the FontRenderContext.
     */
    public abstract FontRenderContext getFontRenderContext();

    /**
     * Gets the current Paint of Graphics2D.
     * 
     * @return the current Paint of Graphics2D.
     */
    public abstract Paint getPaint();

    /**
     * Gets the value of single preference for specified key.
     * 
     * @param key
     *            the specified key of the rendering hint.
     * @return the value of rendering hint for specified key.
     */
    public abstract Object getRenderingHint(RenderingHints.Key key);

    /**
     * Gets the set of the rendering preferences as a collection of key/value
     * pairs.
     * 
     * @return the RenderingHints which contains the rendering preferences.
     */
    public abstract RenderingHints getRenderingHints();

    /**
     * Gets current stroke of the Graphics2D.
     * 
     * @return current stroke of the Graphics2D.
     */
    public abstract Stroke getStroke();

    /**
     * Gets current affine transform of the Graphics2D.
     * 
     * @return current AffineTransform of the Graphics2D.
     */
    public abstract AffineTransform getTransform();

    /**
     * Determines whether or not the specified Shape intersects the specified
     * Rectangle. If the onStroke parameter is true, this method checks whether
     * or not the specified Shape outline intersects the specified Rectangle,
     * otherwise this method checks whether or not the specified Shape's
     * interior intersects the specified Rectangle.
     * 
     * @param rect
     *            the specified Rectangle.
     * @param s
     *            the Shape to check for intersection.
     * @param onStroke
     *            the parameter determines whether or not this method checks for
     *            intersection of the Shape outline or of the Shape interior
     *            with the Rectangle.
     * @return true, if there is a hit, false otherwise.
     */
    public abstract boolean hit(Rectangle rect, Shape s, boolean onStroke);

    /**
     * Performs a rotation transform relative to current Graphics2D Transform.
     * The coordinate system is rotated by the specified angle in radians
     * relative to current origin.
     * 
     * @param theta
     *            the angle of rotation in radians.
     */
    public abstract void rotate(double theta);

    /**
     * Performs a translated rotation transform relative to current Graphics2D
     * Transform. The coordinate system is rotated by the specified angle in
     * radians relative to current origin and then moved to point (x, y). Is
     * this right?
     * 
     * @param theta
     *            the angle of rotation in radians.
     * @param x
     *            the X coordinate.
     * @param y
     *            the Y coordinate.
     */
    public abstract void rotate(double theta, double x, double y);

    /**
     * Performs a linear scale transform relative to current Graphics2D
     * Transform. The coordinate system is rescaled vertically and horizontally
     * by the specified parameters.
     * 
     * @param sx
     *            the scaling factor by which the X coordinate is multiplied.
     * @param sy
     *            the scaling factor by which the Y coordinate is multiplied.
     */
    public abstract void scale(double sx, double sy);

    /**
     * Sets a new background color for clearing rectangular areas. The clearRect
     * method uses the current background color.
     * 
     * @param color
     *            the new background color.
     */
    public abstract void setBackground(Color color);

    /**
     * Sets the current composite for Graphics2D.
     * 
     * @param comp
     *            the Composite object.
     */
    public abstract void setComposite(Composite comp);

    /**
     * Sets the paint for Graphics2D.
     * 
     * @param paint
     *            the Paint object.
     */
    public abstract void setPaint(Paint paint);

    /**
     * Sets a key-value pair in the current RenderingHints map.
     * 
     * @param key
     *            the key of the rendering hint to set.
     * @param value
     *            the value to set for the rendering hint.
     */
    public abstract void setRenderingHint(RenderingHints.Key key, Object value);

    /**
     * Replaces the current rendering hints with the specified rendering
     * preferences.
     * 
     * @param hints
     *            the new Map of rendering hints.
     */
    public abstract void setRenderingHints(Map<?, ?> hints);

    /**
     * Sets the stroke for the Graphics2D.
     * 
     * @param s
     *            the Stroke object.
     */
    public abstract void setStroke(Stroke s);

    /**
     * Overwrite the current Transform of the Graphics2D. The specified
     * Transform should be received from the getTransform() method and should be
     * used only for restoring the original Graphics2D transform after calling
     * draw or fill methods.
     * 
     * @param Tx
     *            the specified Transform.
     */
    public abstract void setTransform(AffineTransform Tx);

    /**
     * Performs a shear transform relative to current Graphics2D Transform. The
     * coordinate system is shifted by the specified multipliers relative to
     * current position.
     * 
     * @param shx
     *            the multiplier by which the X coordinates shift position along
     *            X axis as a function of Y coordinates.
     * @param shy
     *            the multiplier by which the Y coordinates shift position along
     *            Y axis as a function of X coordinates.
     */
    public abstract void shear(double shx, double shy);

    /**
     * Concatenates the AffineTransform object with current Transform of this
     * Graphics2D. The transforms are applied in reverse order with the last
     * specified transform applied first and the next transformation applied to
     * the result of previous transformation. More precisely, if Cx is the
     * current Graphics2D transform, the transform method's result with Tx as
     * the parameter is the transformation Rx, where Rx(p) = Cx(Tx(p)), for p -
     * a point in current coordinate system. Rx becomes the current Transform
     * for this Graphics2D.
     * 
     * @param Tx
     *            the AffineTransform object to be concatenated with current
     *            Transform.
     */
    public abstract void transform(AffineTransform Tx);

    /**
     * Performs a translate transform relative to current Graphics2D Transform.
     * The coordinate system is moved by the specified distance relative to
     * current position.
     * 
     * @param tx
     *            the translation distance along the X axis.
     * @param ty
     *            the translation distance along the Y axis.
     */
    public abstract void translate(double tx, double ty);

    /**
     * Moves the origin Graphics2D Transform to the point with x, y coordinates
     * in current coordinate system. The new origin of coordinate system is
     * moved to the (x, y) point accordingly. All rendering and transform
     * operations are performed relative to this new origin.
     * 
     * @param x
     *            the X coordinate.
     * @param y
     *            the Y coordinate.
     * @see java.awt.Graphics#translate(int, int)
     */
    @Override
    public abstract void translate(int x, int y);

    /**
     * Fills a 3D rectangle with the current color. The rectangle is specified
     * by its width, height, and top left corner coordinates.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     * @param raised
     *            a boolean value that determines whether the rectangle is drawn
     *            as raised or indented.
     * @see java.awt.Graphics#fill3DRect(int, int, int, int, boolean)
     */
    @Override
    public void fill3DRect(int x, int y, int width, int height, boolean raised) {
        // According to the spec, color should be used instead of paint,
        // so Graphics.fill3DRect resets paint and
        // it should be restored after the call
        Paint savedPaint = getPaint();
        super.fill3DRect(x, y, width, height, raised);
        setPaint(savedPaint);
    }

    /**
     * Draws the highlighted outline of a rectangle.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     * @param raised
     *            a boolean value that determines whether the rectangle is drawn
     *            as raised or indented.
     * @see java.awt.Graphics#draw3DRect(int, int, int, int, boolean)
     */
    @Override
    public void draw3DRect(int x, int y, int width, int height, boolean raised) {
        // According to the spec, color should be used instead of paint,
        // so Graphics.draw3DRect resets paint and
        // it should be restored after the call
        Paint savedPaint = getPaint();
        super.draw3DRect(x, y, width, height, raised);
        setPaint(savedPaint);
    }
}
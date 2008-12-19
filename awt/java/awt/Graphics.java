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
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

import java.awt.image.ImageObserver;
import java.text.AttributedCharacterIterator;

/**
 * The abstract Graphics class allows applications to draw on a screen or other
 * rendering target. There are several properties which define rendering
 * options: origin point, clipping area, color, font. <br>
 * <br>
 * The origin point specifies the beginning of the clipping area coordinate
 * system. All coordinates used in rendering operations are computed with
 * respect to this point. The clipping area defines the boundaries where
 * rendering operations can be performed. Rendering operations can't modify
 * pixels outside of the clipping area. <br>
 * <br>
 * The draw and fill methods allow applications to drawing shapes, text, images
 * with specified font and color options in the specified part of the screen.
 * 
 * @since Android 1.0
 */
public abstract class Graphics {

    // Constructors

    /**
     * Instantiates a new Graphics. This constructor is default for Graphics and
     * can not be called directly.
     */
    protected Graphics() {
    }

    // Public methods

    /**
     * Creates a copy of the Graphics object with a new origin and a new
     * specified clip area. The new clip area is the rectangle defined by the
     * origin point with coordinates X,Y and the given width and height. The
     * coordinates of all subsequent rendering operations will be computed with
     * respect to the new origin and can be performed only within the range of
     * the clipping area dimensions.
     * 
     * @param x
     *            the X coordinate of the original point.
     * @param y
     *            the Y coordinate of the original point.
     * @param width
     *            the width of clipping area.
     * @param height
     *            the height of clipping area.
     * @return the Graphics object with new origin point and clipping area.
     */
    public Graphics create(int x, int y, int width, int height) {
        Graphics res = create();
        res.translate(x, y);
        res.clipRect(0, 0, width, height);
        return res;
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
     */
    public void draw3DRect(int x, int y, int width, int height, boolean raised) {
        // Note: lighter/darker colors should be used to draw 3d rect.
        // The resulting rect is (width+1)x(height+1). Stroke and paint
        // attributes of
        // the Graphics2D should be reset to the default values.
        // fillRect is used instead of drawLine to bypass stroke
        // reset/set and rasterization.

        Color color = getColor();
        Color colorUp, colorDown;
        if (raised) {
            colorUp = color.brighter();
            colorDown = color.darker();
        } else {
            colorUp = color.darker();
            colorDown = color.brighter();
        }

        setColor(colorUp);
        fillRect(x, y, width, 1);
        fillRect(x, y + 1, 1, height);

        setColor(colorDown);
        fillRect(x + width, y, 1, height);
        fillRect(x + 1, y + height, width, 1);
    }

    /**
     * Draws the text represented by byte array. This method uses the current
     * font and color for rendering.
     * 
     * @param bytes
     *            the byte array which contains the text to be drawn.
     * @param off
     *            the offset within the byte array of the text to be drawn.
     * @param len
     *            the number of bytes of text to draw.
     * @param x
     *            the X coordinate where the text is to be drawn.
     * @param y
     *            the Y coordinate where the text is to be drawn.
     */
    public void drawBytes(byte[] bytes, int off, int len, int x, int y) {
        drawString(new String(bytes, off, len), x, y);
    }

    /**
     * Draws the text represented by character array. This method uses the
     * current font and color for rendering.
     * 
     * @param chars
     *            the character array.
     * @param off
     *            the offset within the character array of the text to be drawn.
     * @param len
     *            the number of characters which will be drawn.
     * @param x
     *            the X coordinate where the text is to be drawn.
     * @param y
     *            the Y coordinate where the text is to be drawn.
     */
    public void drawChars(char[] chars, int off, int len, int x, int y) {
        drawString(new String(chars, off, len), x, y);
    }

    /**
     * Draws the outline of a polygon which is defined by Polygon object.
     * 
     * @param p
     *            the Polygon object.
     */
    public void drawPolygon(Polygon p) {
        drawPolygon(p.xpoints, p.ypoints, p.npoints);
    }

    /**
     * Draws the rectangle with the specified width and length and top left
     * corner coordinates.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     */
    public void drawRect(int x, int y, int width, int height) {
        int[] xpoints = {
                x, x, x + width, x + width
        };
        int[] ypoints = {
                y, y + height, y + height, y
        };

        drawPolygon(xpoints, ypoints, 4);
    }

    /**
     * Fills the highlighted outline of a rectangle.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     * @param raised
     *            a boolean value that determines whether the rectangle is drawn
     *            as raised or indented.
     */
    public void fill3DRect(int x, int y, int width, int height, boolean raised) {
        // Note: lighter/darker colors should be used to draw 3d rect.
        // The resulting rect is (width)x(height), same as fillRect.
        // Stroke and paint attributes of the Graphics2D should be reset
        // to the default values. fillRect is used instead of drawLine to
        // bypass stroke reset/set and line rasterization.

        Color color = getColor();
        Color colorUp, colorDown;
        if (raised) {
            colorUp = color.brighter();
            colorDown = color.darker();
            setColor(color);
        } else {
            colorUp = color.darker();
            colorDown = color.brighter();
            setColor(colorUp);
        }

        width--;
        height--;
        fillRect(x + 1, y + 1, width - 1, height - 1);

        setColor(colorUp);
        fillRect(x, y, width, 1);
        fillRect(x, y + 1, 1, height);

        setColor(colorDown);
        fillRect(x + width, y, 1, height);
        fillRect(x + 1, y + height, width, 1);
    }

    /**
     * Fills the polygon with the current color.
     * 
     * @param p
     *            the Polygon object.
     */
    public void fillPolygon(Polygon p) {
        fillPolygon(p.xpoints, p.ypoints, p.npoints);
    }

    /**
     * Disposes of the Graphics.
     */
    @Override
    public void finalize() {
    }

    /**
     * Gets the bounds of the current clipping area as a rectangle and copies it
     * to an existing rectangle.
     * 
     * @param r
     *            a Rectangle object where the current clipping area bounds are
     *            to be copied.
     * @return the bounds of the current clipping area.
     */
    public Rectangle getClipBounds(Rectangle r) {
        Shape clip = getClip();

        if (clip != null) {
            // TODO: Can we get shape bounds without creating Rectangle object?
            Rectangle b = clip.getBounds();
            r.x = b.x;
            r.y = b.y;
            r.width = b.width;
            r.height = b.height;
        }

        return r;
    }

    /**
     * Gets the bounds of the current clipping area as a rectangle.
     * 
     * @return a Rectangle object.
     * @deprecated Use {@link #getClipBounds()}
     */
    @Deprecated
    public Rectangle getClipRect() {
        return getClipBounds();
    }

    /**
     * Gets the font metrics of the current font. The font metrics object
     * contains information about the rendering of a particular font.
     * 
     * @return the font metrics of current font.
     */
    public FontMetrics getFontMetrics() {
        return getFontMetrics(getFont());
    }

    /**
     * Determines whether or not the specified rectangle intersects the current
     * clipping area.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     * @return true, if the specified rectangle intersects the current clipping
     *         area, false otherwise.
     */
    public boolean hitClip(int x, int y, int width, int height) {
        // TODO: Create package private method Rectangle.intersects(int, int,
        // int, int);
        return getClipBounds().intersects(new Rectangle(x, y, width, height));
    }

    /**
     * Returns string which represents this Graphics object.
     * 
     * @return the string which represents this Graphics object.
     */
    @Override
    public String toString() {
        // TODO: Think about string representation of Graphics.
        return "Graphics"; //$NON-NLS-1$
    }

    // Abstract methods

    /**
     * Clears the specified rectangle. This method fills specified rectangle
     * with background color.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     */
    public abstract void clearRect(int x, int y, int width, int height);

    /**
     * Intersects the current clipping area with a new rectangle. If the current
     * clipping area is not defined, the rectangle becomes a new clipping area.
     * Rendering operations are only allowed within the new the clipping area.
     * 
     * @param x
     *            the X coordinate of the rectangle for intersection.
     * @param y
     *            the Y coordinate of the rectangle for intersection.
     * @param width
     *            the width of the rectangle for intersection.
     * @param height
     *            the height of the rectangle for intersection.
     */
    public abstract void clipRect(int x, int y, int width, int height);

    /**
     * Copies the rectangle area to another area specified by a distance (dx,
     * dy) from the original rectangle's location. Positive dx and dy values
     * give a new location defined by translation to the right and down from the
     * original location, negative dx and dy values - to the left and up.
     * 
     * @param sx
     *            the X coordinate of the rectangle which will be copied.
     * @param sy
     *            the Y coordinate of the rectangle which will be copied.
     * @param width
     *            the width of the rectangle which will be copied.
     * @param height
     *            the height of the rectangle which will be copied.
     * @param dx
     *            the horizontal distance from the source rectangle's location
     *            to the copy's location.
     * @param dy
     *            the vertical distance from the source rectangle's location to
     *            the copy's location.
     */
    public abstract void copyArea(int sx, int sy, int width, int height, int dx, int dy);

    /**
     * Creates a new copy of this Graphics.
     * 
     * @return a new Graphics context which is a copy of this Graphics.
     */
    public abstract Graphics create();

    /**
     * Disposes of the Graphics. This Graphics object can not be used after
     * calling this method.
     */
    public abstract void dispose();

    /**
     * Draws the arc covering the specified rectangle and using the current
     * color. The rectangle is defined by the origin point (X, Y) and dimensions
     * (width and height). The arc center is the the center of specified
     * rectangle. The angle origin is 3 o'clock position, the positive angle is
     * counted as a counter-clockwise rotation, the negative angle is counted as
     * clockwise rotation.
     * 
     * @param x
     *            the X origin coordinate of the rectangle which scales the arc.
     * @param y
     *            the Y origin coordinate of the rectangle which scales the arc.
     * @param width
     *            the width of the rectangle which scales the arc.
     * @param height
     *            the height of the rectangle which scales the arc.
     * @param sa
     *            start angle - the origin angle of arc.
     * @param ea
     *            arc angle - the angular arc value relative to the start angle.
     */
    public abstract void drawArc(int x, int y, int width, int height, int sa, int ea);

    /**
     * Draws the specified image with the defined background color. The top left
     * corner of image will be drawn at point (x, y) in current coordinate
     * system. The image loading process notifies the specified Image Observer.
     * This method returns true if the image has loaded, otherwise it returns
     * false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param x
     *            the X coordinate of the image top left corner.
     * @param y
     *            the Y coordinate of the image top left corner.
     * @param bgcolor
     *            the background color.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, false
     *         otherwise.
     */
    public abstract boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer);

    /**
     * Draws the specified image. The top left corner of image will be drawn at
     * point (x, y) in current coordinate system. The image loading process
     * notifies the specified Image Observer. This method returns true if the
     * image has loaded, otherwise it returns false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param x
     *            the X coordinate of the image top left corner.
     * @param y
     *            the Y coordinate of the image top left corner.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, otherwise
     *         false.
     */
    public abstract boolean drawImage(Image img, int x, int y, ImageObserver observer);

    /**
     * Scales the specified image to fit in the specified rectangle and draws it
     * with the defined background color. The top left corner of the image will
     * be drawn at the point (x, y) in current coordinate system. The non-opaque
     * pixels will be drawn in the background color. The image loading process
     * notifies the specified Image Observer. This method returns true if the
     * image has loaded, otherwise it returns false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param x
     *            the X coordinate of the image's top left corner.
     * @param y
     *            the Y coordinate of the image's top left corner.
     * @param width
     *            the width of rectangle which scales the image.
     * @param height
     *            the height of rectangle which scales the image.
     * @param bgcolor
     *            the background color.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, otherwise
     *         false.
     */
    public abstract boolean drawImage(Image img, int x, int y, int width, int height,
            Color bgcolor, ImageObserver observer);

    /**
     * Scales the specified image to fit in the specified rectangle and draws
     * it. The top left corner of the image will be drawn at the point (x, y) in
     * current coordinate system. The image loading process notifies the
     * specified Image Observer. This method returns true if the image has
     * loaded, otherwise it returns false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param x
     *            the X coordinate of the image top left corner.
     * @param y
     *            the Y coordinate of the image top left corner.
     * @param width
     *            the width of rectangle which scales the image.
     * @param height
     *            the height of rectangle which scales the image.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, otherwise
     *         false.
     */
    public abstract boolean drawImage(Image img, int x, int y, int width, int height,
            ImageObserver observer);

    /**
     * Scales the specified area of the specified image to fit in the rectangle
     * area defined by its corners coordinates and draws the sub-image with the
     * specified background color. The sub-image to be drawn is defined by its
     * top left corner coordinates (sx1, sy1) and bottom right corner
     * coordinates (sx2, sy2) computed with respect to the origin (top left
     * corner) of the source image. The non opaque pixels will be drawn in the
     * background color. The image loading process notifies specified Image
     * Observer. This method returns true if the image has loaded, otherwise it
     * returns false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param dx1
     *            the X top left corner coordinate of the destination rectangle
     *            area.
     * @param dy1
     *            the Y top left corner coordinate of the destination rectangle
     *            area.
     * @param dx2
     *            the X bottom right corner coordinate of the destination
     *            rectangle area.
     * @param dy2
     *            the Y bottom right corner coordinate of the destination
     *            rectangle area.
     * @param sx1
     *            the X top left corner coordinate of the area to be drawn
     *            within the source image.
     * @param sy1
     *            the Y top left corner coordinate of the area to be drawn
     *            within the source image.
     * @param sx2
     *            the X bottom right corner coordinate of the area to be drawn
     *            within the source image.
     * @param sy2
     *            the Y bottom right corner coordinate of the area to be drawn
     *            within the source image.
     * @param bgcolor
     *            the background color.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, false
     *         otherwise.
     */
    public abstract boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1,
            int sy1, int sx2, int sy2, Color bgcolor, ImageObserver observer);

    /**
     * Scales the specified area of the specified image to fit in the rectangle
     * area defined by its corners coordinates and draws the sub-image. The
     * sub-image to be drawn is defined by its top left corner coordinates (sx1,
     * sy1) and bottom right corner coordinates (sx2, sy2) computed with respect
     * to the origin (top left corner) of the source image. The image loading
     * process notifies specified Image Observer. This method returns true if
     * the image has loaded, otherwise it returns false.
     * 
     * @param img
     *            the image which will be drawn.
     * @param dx1
     *            the X top left corner coordinate of the destination rectangle
     *            area.
     * @param dy1
     *            the Y top left corner coordinate of the destination rectangle
     *            area.
     * @param dx2
     *            the X bottom right corner coordinate of the destination
     *            rectangle area.
     * @param dy2
     *            the Y bottom right corner coordinate of the destination
     *            rectangle area.
     * @param sx1
     *            the X top left corner coordinate of the area to be drawn
     *            within the source image.
     * @param sy1
     *            the Y top left corner coordinate of the area to be drawn
     *            within the source image.
     * @param sx2
     *            the X bottom right corner coordinate of the area to be drawn
     *            within the source image.
     * @param sy2
     *            the Y bottom right corner coordinate of the area to be drawn
     *            within the source image.
     * @param observer
     *            the ImageObserver object which should be notified about image
     *            loading process.
     * @return true, if loading image is successful or image is null, false
     *         otherwise.
     */
    public abstract boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1,
            int sy1, int sx2, int sy2, ImageObserver observer);

    /**
     * Draws a line from the point (x1, y1) to the point (x2, y2). This method
     * draws the line with current color which can be changed by setColor(Color
     * c) method.
     * 
     * @param x1
     *            the X coordinate of the first point.
     * @param y1
     *            the Y coordinate of the first point.
     * @param x2
     *            the X coordinate of the second point.
     * @param y2
     *            the Y coordinate of the second point.
     */
    public abstract void drawLine(int x1, int y1, int x2, int y2);

    /**
     * Draws the outline of an oval to fit in the rectangle defined by the given
     * width, height, and top left corner.
     * 
     * @param x
     *            the X top left corner oval coordinate.
     * @param y
     *            the Y top left corner oval coordinate.
     * @param width
     *            the oval width.
     * @param height
     *            the oval height.
     */
    public abstract void drawOval(int x, int y, int width, int height);

    /**
     * Draws the outline of a polygon. The polygon vertices are defined by
     * points with xpoints[i], ypoints[i] as coordinates. The polygon edges are
     * the lines from the points with (xpoints[i-1], ypoints[i-1]) coordinates
     * to the points with (xpoints[i], ypoints[i]) coordinates, for 0 < i <
     * npoints +1.
     * 
     * @param xpoints
     *            the array of X coordinates of the polygon vertices.
     * @param ypoints
     *            the array of Y coordinates of the polygon vertices.
     * @param npoints
     *            the number of polygon vertices/points.
     */
    public abstract void drawPolygon(int[] xpoints, int[] ypoints, int npoints);

    /**
     * Draws a set of connected lines which are defined by the x and y
     * coordinate arrays. The polyline is closed if coordinates of the first
     * point are the same as coordinates of the last point.
     * 
     * @param xpoints
     *            the array of X point coordinates.
     * @param ypoints
     *            the array of Y point coordinates.
     * @param npoints
     *            the number of points.
     */
    public abstract void drawPolyline(int[] xpoints, int[] ypoints, int npoints);

    /**
     * Draws the outline of a rectangle with round corners.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of the rectangle.
     * @param height
     *            the height of the rectangle.
     * @param arcWidth
     *            the arc width for the corners.
     * @param arcHeight
     *            the arc height for the corners.
     */
    public abstract void drawRoundRect(int x, int y, int width, int height, int arcWidth,
            int arcHeight);

    /**
     * Draws a text defined by an iterator. The iterator should specify the font
     * for every character.
     * 
     * @param iterator
     *            the iterator.
     * @param x
     *            the X coordinate of the first character.
     * @param y
     *            the Y coordinate of the first character.
     */
    public abstract void drawString(AttributedCharacterIterator iterator, int x, int y);

    /**
     * Draws a text defined by a string. This method draws the text with current
     * font and color.
     * 
     * @param str
     *            the string.
     * @param x
     *            the X coordinate of the first character.
     * @param y
     *            the Y coordinate of the first character.
     */
    public abstract void drawString(String str, int x, int y);

    /**
     * Fills the arc covering the rectangle and using the current color. The
     * rectangle is defined by the origin point (X, Y) and dimensions (width and
     * height). The arc center is the the center of specified rectangle. The
     * angle origin is at the 3 o'clock position, and a positive angle gives
     * counter-clockwise rotation, a negative angle gives clockwise rotation.
     * 
     * @param x
     *            the X origin coordinate of the rectangle which scales the arc.
     * @param y
     *            the Y origin coordinate of the rectangle which scales the arc.
     * @param width
     *            the width of the rectangle which scales the arc.
     * @param height
     *            the height of the rectangle which scales the arc.
     * @param sa
     *            start angle - the origin angle of arc.
     * @param ea
     *            arc angle - the angular arc value relative to the start angle.
     */
    public abstract void fillArc(int x, int y, int width, int height, int sa, int ea);

    /**
     * Fills an oval with the current color where the oval is defined by the
     * bounding rectangle with the given width, height, and top left corner.
     * 
     * @param x
     *            the X top left corner oval coordinate.
     * @param y
     *            the Y top left corner oval coordinate.
     * @param width
     *            the oval width.
     * @param height
     *            the oval height.
     */
    public abstract void fillOval(int x, int y, int width, int height);

    /**
     * Fills a polygon with the current color. The polygon vertices are defined
     * by the points with xpoints[i], ypoints[i] as coordinates. The polygon
     * edges are the lines from the points with (xpoints[i-1], ypoints[i-1])
     * coordinates to the points with (xpoints[i], ypoints[i]) coordinates, for
     * 0 < i < npoints +1.
     * 
     * @param xpoints
     *            the array of X coordinates of the polygon vertices.
     * @param ypoints
     *            the array of Y coordinates of the polygon vertices.
     * @param npoints
     *            the number of polygon vertices/points.
     */
    public abstract void fillPolygon(int[] xpoints, int[] ypoints, int npoints);

    /**
     * Fills a rectangle with the current color. The rectangle is defined by its
     * width and length and top left corner coordinates.
     * 
     * @param x
     *            the X coordinate of the rectangle's top left corner.
     * @param y
     *            the Y coordinate of the rectangle's top left corner.
     * @param width
     *            the width of rectangle.
     * @param height
     *            the height of rectangle.
     */
    public abstract void fillRect(int x, int y, int width, int height);

    /**
     * Fills a round cornered rectangle with the current color.
     * 
     * @param x
     *            the X coordinate of the top left corner of the bounding
     *            rectangle.
     * @param y
     *            the Y coordinate of the top left corner of the bounding
     *            rectangle.
     * @param width
     *            the width of the bounding rectangle.
     * @param height
     *            the height of the bounding rectangle.
     * @param arcWidth
     *            the arc width at the corners.
     * @param arcHeight
     *            the arc height at the corners.
     */
    public abstract void fillRoundRect(int x, int y, int width, int height, int arcWidth,
            int arcHeight);

    /**
     * Gets the clipping area. <br>
     * <br>
     * 
     * @return a Shape object of the clipping area or null if it is not set.
     */
    public abstract Shape getClip();

    /**
     * Gets the bounds of the current clipping area as a rectangle.
     * 
     * @return a Rectangle object which represents the bounds of the current
     *         clipping area.
     */
    public abstract Rectangle getClipBounds();

    /**
     * Gets the current color of Graphics.
     * 
     * @return the current color.
     */
    public abstract Color getColor();

    /**
     * Gets the current font of Graphics.
     * 
     * @return the current font.
     */
    public abstract Font getFont();

    /**
     * Gets the font metrics of the specified font. The font metrics object
     * contains information about the rendering of a particular font.
     * 
     * @param font
     *            the specified font.
     * @return the font metrics for the specified font.
     */
    public abstract FontMetrics getFontMetrics(Font font);

    /**
     * Sets the new clipping area specified by rectangle. The new clipping area
     * doesn't depend on the window's visibility. Rendering operations can't be
     * performed outside new clipping area.
     * 
     * @param x
     *            the X coordinate of the new clipping rectangle.
     * @param y
     *            the Y coordinate of the new clipping rectangle.
     * @param width
     *            the width of the new clipping rectangle.
     * @param height
     *            the height of the new clipping rectangle.
     */
    public abstract void setClip(int x, int y, int width, int height);

    /**
     * Sets the new clipping area to be the area specified by Shape object. The
     * new clipping area doesn't depend on the window's visibility. Rendering
     * operations can't be performed outside new clipping area.
     * 
     * @param clip
     *            the Shape object which represents new clipping area.
     */
    public abstract void setClip(Shape clip);

    /**
     * Sets the current Graphics color. All rendering operations with this
     * Graphics will use this color.
     * 
     * @param c
     *            the new color.
     */
    public abstract void setColor(Color c);

    /**
     * Sets the current Graphics font. All rendering operations with this
     * Graphics will use this font.
     * 
     * @param font
     *            the new font.
     */
    public abstract void setFont(Font font);

    /**
     * Sets the paint mode for the Graphics which overwrites all rendering
     * operations with the current color.
     */
    public abstract void setPaintMode();

    /**
     * Sets the XOR mode for the Graphics which changes a pixel from the current
     * color to the specified XOR color. <br>
     * <br>
     * 
     * @param color
     *            the new XOR mode.
     */
    public abstract void setXORMode(Color color);

    /**
     * Translates the origin of Graphics current coordinate system to the point
     * with X, Y coordinates in the current coordinate system. All rendering
     * operation in this Graphics will be related to the new origin.
     * 
     * @param x
     *            the X coordinate of the origin.
     * @param y
     *            the Y coordinate of the origin.
     */
    public abstract void translate(int x, int y);
}

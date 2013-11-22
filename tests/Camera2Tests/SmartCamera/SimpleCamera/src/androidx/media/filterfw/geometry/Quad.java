/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media.filterfw.geometry;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;

/**
 * The Quad class specifies a (possibly affine transformed) rectangle.
 *
 * A Quad instance holds 4 points that define its shape. The points may represent any rectangle that
 * has been transformed by an affine transformation. This means that Quads can represent translated,
 * scaled, rotated and sheared/skewed rectangles. As such, Quads are restricted to the set of
 * parallelograms.
 *
 * Each point in the Quad represents a specific corner of the Quad. These are top-left, top-right,
 * bottom-left, and bottom-right. These labels allow mapping a transformed Quad back to an up-right
 * Quad, with the point-to-point mapping well-defined. They do not necessarily indicate that e.g.
 * the top-left corner is actually at the top-left of coordinate space.
 */
@SuppressLint("FloatMath")
public class Quad {

    private final PointF mTopLeft;
    private final PointF mTopRight;
    private final PointF mBottomLeft;
    private final PointF mBottomRight;

    /**
     * Returns the unit Quad.
     * The unit Quad has its top-left point at (0, 0) and bottom-right point at (1, 1).
     * @return the unit Quad.
     */
    public static Quad unitQuad() {
        return new Quad(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f);
    }

    /**
     * Return a Quad from the specified rectangle.
     *
     * @param rect a RectF instance.
     * @return Quad that represents the passed rectangle.
     */
    public static Quad fromRect(RectF rect) {
        return new Quad(new PointF(rect.left, rect.top),
                        new PointF(rect.right, rect.top),
                        new PointF(rect.left, rect.bottom),
                        new PointF(rect.right, rect.bottom));
    }

    /**
     * Return a Quad from the specified rectangle coordinates.
     *
     * @param x the top left x coordinate
     * @param y the top left y coordinate
     * @param width the width of the rectangle
     * @param height the height of the rectangle
     * @return Quad that represents the passed rectangle.
     */
    public static Quad fromRect(float x, float y, float width, float height) {
        return new Quad(new PointF(x, y),
                        new PointF(x + width, y),
                        new PointF(x, y + height),
                        new PointF(x + width, y + height));
    }

    /**
     * Return a Quad that spans the specified points and height.
     *
     * The returned Quad has the specified top-left and top-right points, and the specified height
     * while maintaining 90 degree angles on all 4 corners.
     *
     * @param topLeft the top-left of the quad
     * @param topRight the top-right of the quad
     * @param height the height of the quad
     * @return Quad that spans the specified points and height.
     */
    public static Quad fromLineAndHeight(PointF topLeft, PointF topRight, float height) {
        PointF dp = new PointF(topRight.x - topLeft.x, topRight.y - topLeft.y);
        float len = dp.length();
        PointF np = new PointF(height * (dp.y / len), height * (dp.x / len));
        PointF p2 = new PointF(topLeft.x - np.x, topLeft.y + np.y);
        PointF p3 = new PointF(topRight.x - np.x, topRight.y + np.y);
        return new Quad(topLeft, topRight, p2, p3);
    }

    /**
     * Return a Quad that represents the specified rotated rectangle.
     *
     * The Quad is rotated counter-clockwise around its centroid.
     *
     * @param rect the source rectangle
     * @param angle the angle to rotate the source rectangle in radians
     * @return the Quad representing the source rectangle rotated by the given angle.
     */
    public static Quad fromRotatedRect(RectF rect, float angle) {
        return Quad.fromRect(rect).rotated(angle);
    }

    /**
     * Return a Quad that represents the specified transformed rectangle.
     *
     * The transform is applied by multiplying each point (x, y, 1) by the matrix.
     *
     * @param rect the source rectangle
     * @param matrix the transformation matrix
     * @return the Quad representing the source rectangle transformed by the matrix
     */
    public static Quad fromTransformedRect(RectF rect, Matrix matrix) {
        return Quad.fromRect(rect).transformed(matrix);
    }

    /**
     * Returns the transformation matrix to transform the source Quad to the target Quad.
     *
     * @param source the source quad
     * @param target the target quad
     * @return the transformation matrix to map source to target.
     */
    public static Matrix getTransform(Quad source, Quad target) {
        // We only use the first 3 points as they sufficiently specify the transform
        Matrix transform = new Matrix();
        transform.setPolyToPoly(source.asCoords(), 0, target.asCoords(), 0, 3);
        return transform;
    }

    /**
     * The top-left point of the Quad.
     * @return top-left point of the Quad.
     */
    public PointF topLeft() {
        return mTopLeft;
    }

    /**
     * The top-right point of the Quad.
     * @return top-right point of the Quad.
     */
    public PointF topRight() {
        return mTopRight;
    }

    /**
     * The bottom-left point of the Quad.
     * @return bottom-left point of the Quad.
     */
    public PointF bottomLeft() {
        return mBottomLeft;
    }

    /**
     * The bottom-right point of the Quad.
     * @return bottom-right point of the Quad.
     */
    public PointF bottomRight() {
        return mBottomRight;
    }

    /**
     * Rotate the quad by the given angle.
     *
     * The Quad is rotated counter-clockwise around its centroid.
     *
     * @param angle the angle to rotate in radians
     * @return the rotated Quad
     */
    public Quad rotated(float angle) {
        PointF center = center();
        float cosa = (float) Math.cos(angle);
        float sina = (float) Math.sin(angle);

        PointF topLeft = rotatePoint(topLeft(), center, cosa, sina);
        PointF topRight = rotatePoint(topRight(), center, cosa, sina);
        PointF bottomLeft = rotatePoint(bottomLeft(), center, cosa, sina);
        PointF bottomRight = rotatePoint(bottomRight(), center, cosa, sina);

        return new Quad(topLeft, topRight, bottomLeft, bottomRight);
    }

    /**
     * Transform the quad with the given transformation matrix.
     *
     * The transform is applied by multiplying each point (x, y, 1) by the matrix.
     *
     * @param matrix the transformation matrix
     * @return the transformed Quad
     */
    public Quad transformed(Matrix matrix) {
        float[] points = asCoords();
        matrix.mapPoints(points);
        return new Quad(points);
    }

    /**
     * Returns the centroid of the Quad.
     *
     * The centroid of the Quad is where the two inner diagonals connecting the opposite corners
     * meet.
     *
     * @return the centroid of the Quad.
     */
    public PointF center() {
        // As the diagonals bisect each other, we can simply return the center of one of the
        // diagonals.
        return new PointF((mTopLeft.x + mBottomRight.x) / 2f,
                          (mTopLeft.y + mBottomRight.y) / 2f);
    }

    /**
     * Returns the quad as a float-array of coordinates.
     * The order of coordinates is top-left, top-right, bottom-left, bottom-right. This is the
     * default order of coordinates used in ImageShaders, so this method can be used to bind
     * an attribute to the Quad.
     */
    public float[] asCoords() {
        return new float[] { mTopLeft.x, mTopLeft.y,
                             mTopRight.x, mTopRight.y,
                             mBottomLeft.x, mBottomLeft.y,
                             mBottomRight.x, mBottomRight.y };
    }

    /**
     * Grow the Quad outwards by the specified factor.
     *
     * This method moves the corner points of the Quad outward along the diagonals that connect
     * them to the centroid. A factor of 1.0 moves the quad outwards by the distance of the corners
     * to the centroid.
     *
     * @param factor the growth factor
     * @return the Quad grown by the specified amount
     */
    public Quad grow(float factor) {
        PointF pc = center();
        return new Quad(factor * (mTopLeft.x - pc.x) + pc.x,
                        factor * (mTopLeft.y - pc.y) + pc.y,
                        factor * (mTopRight.x - pc.x) + pc.x,
                        factor * (mTopRight.y - pc.y) + pc.y,
                        factor * (mBottomLeft.x - pc.x) + pc.x,
                        factor * (mBottomLeft.y - pc.y) + pc.y,
                        factor * (mBottomRight.x - pc.x) + pc.x,
                        factor * (mBottomRight.y - pc.y) + pc.y);
    }

    /**
     * Scale the Quad by the specified factor.
     *
     * @param factor the scaling factor
     * @return the Quad instance scaled by the specified factor.
     */
    public Quad scale(float factor) {
        return new Quad(mTopLeft.x * factor, mTopLeft.y * factor,
                        mTopRight.x * factor, mTopRight.y * factor,
                        mBottomLeft.x * factor, mBottomLeft.y * factor,
                        mBottomRight.x * factor, mBottomRight.y * factor);
    }

    /**
     * Scale the Quad by the specified factors in the x and y factors.
     *
     * @param sx the x scaling factor
     * @param sy the y scaling factor
     * @return the Quad instance scaled by the specified factors.
     */
    public Quad scale2(float sx, float sy) {
        return new Quad(mTopLeft.x * sx, mTopLeft.y * sy,
                        mTopRight.x * sx, mTopRight.y * sy,
                        mBottomLeft.x * sx, mBottomLeft.y * sy,
                        mBottomRight.x * sx, mBottomRight.y * sy);
    }

    /**
     * Returns the Quad's left-to-right edge.
     *
     * Returns a vector that goes from the Quad's top-left to top-right (or bottom-left to
     * bottom-right).
     *
     * @return the edge vector as a PointF.
     */
    public PointF xEdge() {
        return new PointF(mTopRight.x - mTopLeft.x, mTopRight.y - mTopLeft.y);
    }

    /**
     * Returns the Quad's top-to-bottom edge.
     *
     * Returns a vector that goes from the Quad's top-left to bottom-left (or top-right to
     * bottom-right).
     *
     * @return the edge vector as a PointF.
     */
    public PointF yEdge() {
        return new PointF(mBottomLeft.x - mTopLeft.x, mBottomLeft.y - mTopLeft.y);
    }

    @Override
    public String toString() {
        return "Quad(" + mTopLeft.x + ", " + mTopLeft.y + ", "
                       + mTopRight.x + ", " + mTopRight.y + ", "
                       + mBottomLeft.x + ", " + mBottomLeft.y + ", "
                       + mBottomRight.x + ", " + mBottomRight.y + ")";
    }

    private Quad(PointF topLeft, PointF topRight, PointF bottomLeft, PointF bottomRight) {
        mTopLeft = topLeft;
        mTopRight = topRight;
        mBottomLeft = bottomLeft;
        mBottomRight = bottomRight;
    }

    private Quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        mTopLeft = new PointF(x0, y0);
        mTopRight = new PointF(x1, y1);
        mBottomLeft = new PointF(x2, y2);
        mBottomRight = new PointF(x3, y3);
    }

    private Quad(float[] points) {
        mTopLeft = new PointF(points[0], points[1]);
        mTopRight = new PointF(points[2], points[3]);
        mBottomLeft = new PointF(points[4], points[5]);
        mBottomRight = new PointF(points[6], points[7]);
    }

    private static PointF rotatePoint(PointF p, PointF c, float cosa, float sina) {
        float x = (p.x - c.x) * cosa - (p.y - c.y) * sina + c.x;
        float y = (p.x - c.x) * sina + (p.y - c.y) * cosa + c.y;
        return new PointF(x,y);
    }
}


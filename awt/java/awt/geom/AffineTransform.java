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
 * @author Denis M. Kishenko
 * @version $Revision$
 */

package java.awt.geom;

import java.awt.Shape;
import java.io.IOException;
import java.io.Serializable;

import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.misc.HashCode;

/**
 * The Class AffineTransform represents a linear transformation (rotation,
 * scaling, or shear) followed by a translation that acts on a coordinate space.
 * It preserves collinearity of points and ratios of distances between collinear
 * points: so if A, B, and C are on a line, then after the space has been
 * transformed via the affine transform, the images of the three points will
 * still be on a line, and the ratio of the distance from A to B with the
 * distance from B to C will be the same as the corresponding ratio in the image
 * space.
 * 
 * @since Android 1.0
 */
public class AffineTransform implements Cloneable, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 1330973210523860834L;

    /**
     * The Constant TYPE_IDENTITY.
     */
    public static final int TYPE_IDENTITY = 0;

    /**
     * The Constant TYPE_TRANSLATION.
     */
    public static final int TYPE_TRANSLATION = 1;

    /**
     * The Constant TYPE_UNIFORM_SCALE.
     */
    public static final int TYPE_UNIFORM_SCALE = 2;

    /**
     * The Constant TYPE_GENERAL_SCALE.
     */
    public static final int TYPE_GENERAL_SCALE = 4;

    /**
     * The Constant TYPE_QUADRANT_ROTATION.
     */
    public static final int TYPE_QUADRANT_ROTATION = 8;

    /**
     * The Constant TYPE_GENERAL_ROTATION.
     */
    public static final int TYPE_GENERAL_ROTATION = 16;

    /**
     * The Constant TYPE_GENERAL_TRANSFORM.
     */
    public static final int TYPE_GENERAL_TRANSFORM = 32;

    /**
     * The Constant TYPE_FLIP.
     */
    public static final int TYPE_FLIP = 64;

    /**
     * The Constant TYPE_MASK_SCALE.
     */
    public static final int TYPE_MASK_SCALE = TYPE_UNIFORM_SCALE | TYPE_GENERAL_SCALE;

    /**
     * The Constant TYPE_MASK_ROTATION.
     */
    public static final int TYPE_MASK_ROTATION = TYPE_QUADRANT_ROTATION | TYPE_GENERAL_ROTATION;

    /**
     * The <code>TYPE_UNKNOWN</code> is an initial type value.
     */
    static final int TYPE_UNKNOWN = -1;

    /**
     * The min value equivalent to zero. If absolute value less then ZERO it
     * considered as zero.
     */
    static final double ZERO = 1E-10;

    /**
     * The values of transformation matrix.
     */
    double m00;

    /**
     * The m10.
     */
    double m10;

    /**
     * The m01.
     */
    double m01;

    /**
     * The m11.
     */
    double m11;

    /**
     * The m02.
     */
    double m02;

    /**
     * The m12.
     */
    double m12;

    /**
     * The transformation <code>type</code>.
     */
    transient int type;

    /**
     * Instantiates a new affine transform of type <code>TYPE_IDENTITY</code>
     * (which leaves coordinates unchanged).
     */
    public AffineTransform() {
        type = TYPE_IDENTITY;
        m00 = m11 = 1.0;
        m10 = m01 = m02 = m12 = 0.0;
    }

    /**
     * Instantiates a new affine transform that has the same data as the given
     * AffineTransform.
     * 
     * @param t
     *            the transform to copy.
     */
    public AffineTransform(AffineTransform t) {
        this.type = t.type;
        this.m00 = t.m00;
        this.m10 = t.m10;
        this.m01 = t.m01;
        this.m11 = t.m11;
        this.m02 = t.m02;
        this.m12 = t.m12;
    }

    /**
     * Instantiates a new affine transform by specifying the values of the 2x3
     * transformation matrix as floats. The type is set to the default type:
     * <code>TYPE_UNKNOWN</code>
     * 
     * @param m00
     *            the m00 entry in the transformation matrix.
     * @param m10
     *            the m10 entry in the transformation matrix.
     * @param m01
     *            the m01 entry in the transformation matrix.
     * @param m11
     *            the m11 entry in the transformation matrix.
     * @param m02
     *            the m02 entry in the transformation matrix.
     * @param m12
     *            the m12 entry in the transformation matrix.
     */
    public AffineTransform(float m00, float m10, float m01, float m11, float m02, float m12) {
        this.type = TYPE_UNKNOWN;
        this.m00 = m00;
        this.m10 = m10;
        this.m01 = m01;
        this.m11 = m11;
        this.m02 = m02;
        this.m12 = m12;
    }

    /**
     * Instantiates a new affine transform by specifying the values of the 2x3
     * transformation matrix as doubles. The type is set to the default type:
     * <code>TYPE_UNKNOWN</code>
     * 
     * @param m00
     *            the m00 entry in the transformation matrix.
     * @param m10
     *            the m10 entry in the transformation matrix.
     * @param m01
     *            the m01 entry in the transformation matrix.
     * @param m11
     *            the m11 entry in the transformation matrix.
     * @param m02
     *            the m02 entry in the transformation matrix.
     * @param m12
     *            the m12 entry in the transformation matrix.
     */
    public AffineTransform(double m00, double m10, double m01, double m11, double m02, double m12) {
        this.type = TYPE_UNKNOWN;
        this.m00 = m00;
        this.m10 = m10;
        this.m01 = m01;
        this.m11 = m11;
        this.m02 = m02;
        this.m12 = m12;
    }

    /**
     * Instantiates a new affine transform by reading the values of the
     * transformation matrix from an array of floats. The mapping from the array
     * to the matrix starts with <code>matrix[0]</code> giving the top-left
     * entry of the matrix and proceeds with the usual left-to-right and
     * top-down ordering.
     * <p>
     * If the array has only four entries, then the two entries of the last row
     * of the transformation matrix default to zero.
     * 
     * @param matrix
     *            the array of four or six floats giving the values of the
     *            matrix.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of the array is 0, 1, 2, 3, or 5.
     */
    public AffineTransform(float[] matrix) {
        this.type = TYPE_UNKNOWN;
        m00 = matrix[0];
        m10 = matrix[1];
        m01 = matrix[2];
        m11 = matrix[3];
        if (matrix.length > 4) {
            m02 = matrix[4];
            m12 = matrix[5];
        }
    }

    /**
     * Instantiates a new affine transform by reading the values of the
     * transformation matrix from an array of doubles. The mapping from the
     * array to the matrix starts with <code>matrix[0]</code> giving the
     * top-left entry of the matrix and proceeds with the usual left-to-right
     * and top-down ordering.
     * <p>
     * If the array has only four entries, then the two entries of the last row
     * of the transformation matrix default to zero.
     * 
     * @param matrix
     *            the array of four or six doubles giving the values of the
     *            matrix.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of the array is 0, 1, 2, 3, or 5.
     */
    public AffineTransform(double[] matrix) {
        this.type = TYPE_UNKNOWN;
        m00 = matrix[0];
        m10 = matrix[1];
        m01 = matrix[2];
        m11 = matrix[3];
        if (matrix.length > 4) {
            m02 = matrix[4];
            m12 = matrix[5];
        }
    }

    /**
     * Returns type of the affine transformation.
     * <p>
     * The type is computed as follows: Label the entries of the transformation
     * matrix as three rows (m00, m01), (m10, m11), and (m02, m12). Then if the
     * original basis vectors are (1, 0) and (0, 1), the new basis vectors after
     * transformation are given by (m00, m01) and (m10, m11), and the
     * translation vector is (m02, m12).
     * <p>
     * The types are classified as follows: <br/> TYPE_IDENTITY - no change<br/>
     * TYPE_TRANSLATION - The translation vector isn't zero<br/>
     * TYPE_UNIFORM_SCALE - The new basis vectors have equal length<br/>
     * TYPE_GENERAL_SCALE - The new basis vectors dont' have equal length<br/>
     * TYPE_FLIP - The new basis vector orientation differs from the original
     * one<br/> TYPE_QUADRANT_ROTATION - The new basis is a rotation of the
     * original by 90, 180, 270, or 360 degrees<br/> TYPE_GENERAL_ROTATION - The
     * new basis is a rotation of the original by an arbitrary angle<br/>
     * TYPE_GENERAL_TRANSFORM - The transformation can't be inverted.<br/>
     * <p>
     * Note that multiple types are possible, thus the types can be combined
     * using bitwise combinations.
     * 
     * @return the type of the Affine Transform.
     */
    public int getType() {
        if (type != TYPE_UNKNOWN) {
            return type;
        }

        int type = 0;

        if (m00 * m01 + m10 * m11 != 0.0) {
            type |= TYPE_GENERAL_TRANSFORM;
            return type;
        }

        if (m02 != 0.0 || m12 != 0.0) {
            type |= TYPE_TRANSLATION;
        } else if (m00 == 1.0 && m11 == 1.0 && m01 == 0.0 && m10 == 0.0) {
            type = TYPE_IDENTITY;
            return type;
        }

        if (m00 * m11 - m01 * m10 < 0.0) {
            type |= TYPE_FLIP;
        }

        double dx = m00 * m00 + m10 * m10;
        double dy = m01 * m01 + m11 * m11;
        if (dx != dy) {
            type |= TYPE_GENERAL_SCALE;
        } else if (dx != 1.0) {
            type |= TYPE_UNIFORM_SCALE;
        }

        if ((m00 == 0.0 && m11 == 0.0) || (m10 == 0.0 && m01 == 0.0 && (m00 < 0.0 || m11 < 0.0))) {
            type |= TYPE_QUADRANT_ROTATION;
        } else if (m01 != 0.0 || m10 != 0.0) {
            type |= TYPE_GENERAL_ROTATION;
        }

        return type;
    }

    /**
     * Gets the scale x entry of the transformation matrix (the upper left
     * matrix entry).
     * 
     * @return the scale x value.
     */
    public double getScaleX() {
        return m00;
    }

    /**
     * Gets the scale y entry of the transformation matrix (the lower right
     * entry of the linear transformation).
     * 
     * @return the scale y value.
     */
    public double getScaleY() {
        return m11;
    }

    /**
     * Gets the shear x entry of the transformation matrix (the upper right
     * entry of the linear transformation).
     * 
     * @return the shear x value.
     */
    public double getShearX() {
        return m01;
    }

    /**
     * Gets the shear y entry of the transformation matrix (the lower left entry
     * of the linear transformation).
     * 
     * @return the shear y value.
     */
    public double getShearY() {
        return m10;
    }

    /**
     * Gets the x coordinate of the translation vector.
     * 
     * @return the x coordinate of the translation vector.
     */
    public double getTranslateX() {
        return m02;
    }

    /**
     * Gets the y coordinate of the translation vector.
     * 
     * @return the y coordinate of the translation vector.
     */
    public double getTranslateY() {
        return m12;
    }

    /**
     * Checks if the AffineTransformation is the identity.
     * 
     * @return true, if the AffineTransformation is the identity.
     */
    public boolean isIdentity() {
        return getType() == TYPE_IDENTITY;
    }

    /**
     * Writes the values of the transformation matrix into the given array of
     * doubles. If the array has length 4, only the linear transformation part
     * will be written into it. If it has length greater than 4, the translation
     * vector will be included as well.
     * 
     * @param matrix
     *            the array to fill with the values of the matrix.
     * @throws ArrayIndexOutOfBoundsException
     *             if the size of the array is 0, 1, 2, 3, or 5.
     */
    public void getMatrix(double[] matrix) {
        matrix[0] = m00;
        matrix[1] = m10;
        matrix[2] = m01;
        matrix[3] = m11;
        if (matrix.length > 4) {
            matrix[4] = m02;
            matrix[5] = m12;
        }
    }

    /**
     * Gets the determinant of the linear transformation matrix.
     * 
     * @return the determinant of the linear transformation matrix.
     */
    public double getDeterminant() {
        return m00 * m11 - m01 * m10;
    }

    /**
     * Sets the transform in terms of a list of double values.
     * 
     * @param m00
     *            the m00 coordinate of the transformation matrix.
     * @param m10
     *            the m10 coordinate of the transformation matrix.
     * @param m01
     *            the m01 coordinate of the transformation matrix.
     * @param m11
     *            the m11 coordinate of the transformation matrix.
     * @param m02
     *            the m02 coordinate of the transformation matrix.
     * @param m12
     *            the m12 coordinate of the transformation matrix.
     */
    public void setTransform(double m00, double m10, double m01, double m11, double m02, double m12) {
        this.type = TYPE_UNKNOWN;
        this.m00 = m00;
        this.m10 = m10;
        this.m01 = m01;
        this.m11 = m11;
        this.m02 = m02;
        this.m12 = m12;
    }

    /**
     * Sets the transform's data to match the data of the transform sent as a
     * parameter.
     * 
     * @param t
     *            the transform that gives the new values.
     */
    public void setTransform(AffineTransform t) {
        type = t.type;
        setTransform(t.m00, t.m10, t.m01, t.m11, t.m02, t.m12);
    }

    /**
     * Sets the transform to the identity transform.
     */
    public void setToIdentity() {
        type = TYPE_IDENTITY;
        m00 = m11 = 1.0;
        m10 = m01 = m02 = m12 = 0.0;
    }

    /**
     * Sets the transformation to a translation alone. Sets the linear part of
     * the transformation to identity and the translation vector to the values
     * sent as parameters. Sets the type to <code>TYPE_IDENTITY</code> if the
     * resulting AffineTransformation is the identity transformation, otherwise
     * sets it to <code>TYPE_TRANSLATION</code>.
     * 
     * @param mx
     *            the distance to translate in the x direction.
     * @param my
     *            the distance to translate in the y direction.
     */
    public void setToTranslation(double mx, double my) {
        m00 = m11 = 1.0;
        m01 = m10 = 0.0;
        m02 = mx;
        m12 = my;
        if (mx == 0.0 && my == 0.0) {
            type = TYPE_IDENTITY;
        } else {
            type = TYPE_TRANSLATION;
        }
    }

    /**
     * Sets the transformation to being a scale alone, eliminating rotation,
     * shear, and translation elements. Sets the type to
     * <code>TYPE_IDENTITY</code> if the resulting AffineTransformation is the
     * identity transformation, otherwise sets it to <code>TYPE_UNKNOWN</code>.
     * 
     * @param scx
     *            the scaling factor in the x direction.
     * @param scy
     *            the scaling factor in the y direction.
     */
    public void setToScale(double scx, double scy) {
        m00 = scx;
        m11 = scy;
        m10 = m01 = m02 = m12 = 0.0;
        if (scx != 1.0 || scy != 1.0) {
            type = TYPE_UNKNOWN;
        } else {
            type = TYPE_IDENTITY;
        }
    }

    /**
     * Sets the transformation to being a shear alone, eliminating rotation,
     * scaling, and translation elements. Sets the type to
     * <code>TYPE_IDENTITY</code> if the resulting AffineTransformation is the
     * identity transformation, otherwise sets it to <code>TYPE_UNKNOWN</code>.
     * 
     * @param shx
     *            the shearing factor in the x direction.
     * @param shy
     *            the shearing factor in the y direction.
     */
    public void setToShear(double shx, double shy) {
        m00 = m11 = 1.0;
        m02 = m12 = 0.0;
        m01 = shx;
        m10 = shy;
        if (shx != 0.0 || shy != 0.0) {
            type = TYPE_UNKNOWN;
        } else {
            type = TYPE_IDENTITY;
        }
    }

    /**
     * Sets the transformation to being a rotation alone, eliminating shearing,
     * scaling, and translation elements. Sets the type to
     * <code>TYPE_IDENTITY</code> if the resulting AffineTransformation is the
     * identity transformation, otherwise sets it to <code>TYPE_UNKNOWN</code>.
     * 
     * @param angle
     *            the angle of rotation in radians.
     */
    public void setToRotation(double angle) {
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        if (Math.abs(cos) < ZERO) {
            cos = 0.0;
            sin = sin > 0.0 ? 1.0 : -1.0;
        } else if (Math.abs(sin) < ZERO) {
            sin = 0.0;
            cos = cos > 0.0 ? 1.0 : -1.0;
        }
        m00 = m11 = cos;
        m01 = -sin;
        m10 = sin;
        m02 = m12 = 0.0;
        type = TYPE_UNKNOWN;
    }

    /**
     * Sets the transformation to being a rotation followed by a translation.
     * Sets the type to <code>TYPE_UNKNOWN</code>.
     * 
     * @param angle
     *            the angle of rotation in radians.
     * @param px
     *            the distance to translate in the x direction.
     * @param py
     *            the distance to translate in the y direction.
     */
    public void setToRotation(double angle, double px, double py) {
        setToRotation(angle);
        m02 = px * (1.0 - m00) + py * m10;
        m12 = py * (1.0 - m00) - px * m10;
        type = TYPE_UNKNOWN;
    }

    /**
     * Creates a new AffineTransformation that is a translation alone with the
     * translation vector given by the values sent as parameters. The new
     * transformation's type is <code>TYPE_IDENTITY</code> if the
     * AffineTransformation is the identity transformation, otherwise it's
     * <code>TYPE_TRANSLATION</code>.
     * 
     * @param mx
     *            the distance to translate in the x direction.
     * @param my
     *            the distance to translate in the y direction.
     * @return the new AffineTransformation.
     */
    public static AffineTransform getTranslateInstance(double mx, double my) {
        AffineTransform t = new AffineTransform();
        t.setToTranslation(mx, my);
        return t;
    }

    /**
     * Creates a new AffineTransformation that is a scale alone. The new
     * transformation's type is <code>TYPE_IDENTITY</code> if the
     * AffineTransformation is the identity transformation, otherwise it's
     * <code>TYPE_UNKNOWN</code>.
     * 
     * @param scx
     *            the scaling factor in the x direction.
     * @param scY
     *            the scaling factor in the y direction.
     * @return the new AffineTransformation.
     */
    public static AffineTransform getScaleInstance(double scx, double scY) {
        AffineTransform t = new AffineTransform();
        t.setToScale(scx, scY);
        return t;
    }

    /**
     * Creates a new AffineTransformation that is a shear alone. The new
     * transformation's type is <code>TYPE_IDENTITY</code> if the
     * AffineTransformation is the identity transformation, otherwise it's
     * <code>TYPE_UNKNOWN</code>.
     * 
     * @param shx
     *            the shearing factor in the x direction.
     * @param shy
     *            the shearing factor in the y direction.
     * @return the new AffineTransformation.
     */
    public static AffineTransform getShearInstance(double shx, double shy) {
        AffineTransform m = new AffineTransform();
        m.setToShear(shx, shy);
        return m;
    }

    /**
     * Creates a new AffineTransformation that is a rotation alone. The new
     * transformation's type is <code>TYPE_IDENTITY</code> if the
     * AffineTransformation is the identity transformation, otherwise it's
     * <code>TYPE_UNKNOWN</code>.
     * 
     * @param angle
     *            the angle of rotation in radians.
     * @return the new AffineTransformation.
     */
    public static AffineTransform getRotateInstance(double angle) {
        AffineTransform t = new AffineTransform();
        t.setToRotation(angle);
        return t;
    }

    /**
     * Creates a new AffineTransformation that is a rotation followed by a
     * translation. Sets the type to <code>TYPE_UNKNOWN</code>.
     * 
     * @param angle
     *            the angle of rotation in radians.
     * @param x
     *            the distance to translate in the x direction.
     * @param y
     *            the distance to translate in the y direction.
     * @return the new AffineTransformation.
     */
    public static AffineTransform getRotateInstance(double angle, double x, double y) {
        AffineTransform t = new AffineTransform();
        t.setToRotation(angle, x, y);
        return t;
    }

    /**
     * Applies a translation to this AffineTransformation.
     * 
     * @param mx
     *            the distance to translate in the x direction.
     * @param my
     *            the distance to translate in the y direction.
     */
    public void translate(double mx, double my) {
        concatenate(AffineTransform.getTranslateInstance(mx, my));
    }

    /**
     * Applies a scaling transformation to this AffineTransformation.
     * 
     * @param scx
     *            the scaling factor in the x direction.
     * @param scy
     *            the scaling factor in the y direction.
     */
    public void scale(double scx, double scy) {
        concatenate(AffineTransform.getScaleInstance(scx, scy));
    }

    /**
     * Applies a shearing transformation to this AffineTransformation.
     * 
     * @param shx
     *            the shearing factor in the x direction.
     * @param shy
     *            the shearing factor in the y direction.
     */
    public void shear(double shx, double shy) {
        concatenate(AffineTransform.getShearInstance(shx, shy));
    }

    /**
     * Applies a rotation transformation to this AffineTransformation.
     * 
     * @param angle
     *            the angle of rotation in radians.
     */
    public void rotate(double angle) {
        concatenate(AffineTransform.getRotateInstance(angle));
    }

    /**
     * Applies a rotation and translation transformation to this
     * AffineTransformation.
     * 
     * @param angle
     *            the angle of rotation in radians.
     * @param px
     *            the distance to translate in the x direction.
     * @param py
     *            the distance to translate in the y direction.
     */
    public void rotate(double angle, double px, double py) {
        concatenate(AffineTransform.getRotateInstance(angle, px, py));
    }

    /**
     * Multiplies the matrix representations of two AffineTransform objects.
     * 
     * @param t1
     *            - the AffineTransform object is a multiplicand
     * @param t2
     *            - the AffineTransform object is a multiplier
     * @return an AffineTransform object that is the result of t1 multiplied by
     *         the matrix t2.
     */
    AffineTransform multiply(AffineTransform t1, AffineTransform t2) {
        return new AffineTransform(t1.m00 * t2.m00 + t1.m10 * t2.m01, // m00
                t1.m00 * t2.m10 + t1.m10 * t2.m11, // m01
                t1.m01 * t2.m00 + t1.m11 * t2.m01, // m10
                t1.m01 * t2.m10 + t1.m11 * t2.m11, // m11
                t1.m02 * t2.m00 + t1.m12 * t2.m01 + t2.m02, // m02
                t1.m02 * t2.m10 + t1.m12 * t2.m11 + t2.m12);// m12
    }

    /**
     * Applies the given AffineTransform to this AffineTransform via matrix
     * multiplication.
     * 
     * @param t
     *            the AffineTransform to apply to this AffineTransform.
     */
    public void concatenate(AffineTransform t) {
        setTransform(multiply(t, this));
    }

    /**
     * Changes the current AffineTransform the one obtained by taking the
     * transform t and applying this AffineTransform to it.
     * 
     * @param t
     *            the AffineTransform that this AffineTransform is multiplied
     *            by.
     */
    public void preConcatenate(AffineTransform t) {
        setTransform(multiply(this, t));
    }

    /**
     * Creates an AffineTransform that is the inverse of this transform.
     * 
     * @return the affine transform that is the inverse of this AffineTransform.
     * @throws NoninvertibleTransformException
     *             if this AffineTransform cannot be inverted (the determinant
     *             of the linear transformation part is zero).
     */
    public AffineTransform createInverse() throws NoninvertibleTransformException {
        double det = getDeterminant();
        if (Math.abs(det) < ZERO) {
            // awt.204=Determinant is zero
            throw new NoninvertibleTransformException(Messages.getString("awt.204")); //$NON-NLS-1$
        }
        return new AffineTransform(m11 / det, // m00
                -m10 / det, // m10
                -m01 / det, // m01
                m00 / det, // m11
                (m01 * m12 - m11 * m02) / det, // m02
                (m10 * m02 - m00 * m12) / det // m12
        );
    }

    /**
     * Apply the current AffineTransform to the point.
     * 
     * @param src
     *            the original point.
     * @param dst
     *            Point2D object to be filled with the destination coordinates
     *            (where the original point is sent by this AffineTransform).
     *            May be null.
     * @return the point in the AffineTransform's image space where the original
     *         point is sent.
     */
    public Point2D transform(Point2D src, Point2D dst) {
        if (dst == null) {
            if (src instanceof Point2D.Double) {
                dst = new Point2D.Double();
            } else {
                dst = new Point2D.Float();
            }
        }

        double x = src.getX();
        double y = src.getY();

        dst.setLocation(x * m00 + y * m01 + m02, x * m10 + y * m11 + m12);
        return dst;
    }

    /**
     * Applies this AffineTransform to an array of points.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length > src.length</code> or
     *             <code>dstOff + length > dst.length</code>.
     */
    public void transform(Point2D[] src, int srcOff, Point2D[] dst, int dstOff, int length) {
        while (--length >= 0) {
            Point2D srcPoint = src[srcOff++];
            double x = srcPoint.getX();
            double y = srcPoint.getY();
            Point2D dstPoint = dst[dstOff];
            if (dstPoint == null) {
                if (srcPoint instanceof Point2D.Double) {
                    dstPoint = new Point2D.Double();
                } else {
                    dstPoint = new Point2D.Float();
                }
            }
            dstPoint.setLocation(x * m00 + y * m01 + m02, x * m10 + y * m11 + m12);
            dst[dstOff++] = dstPoint;
        }
    }

    /**
     * Applies this AffineTransform to a set of points given as an array of
     * double values where every two values in the array give the coordinates of
     * a point; the even-indexed values giving the x coordinates and the
     * odd-indexed values giving the y coordinates.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     */
    public void transform(double[] src, int srcOff, double[] dst, int dstOff, int length) {
        int step = 2;
        if (src == dst && srcOff < dstOff && dstOff < srcOff + length * 2) {
            srcOff = srcOff + length * 2 - 2;
            dstOff = dstOff + length * 2 - 2;
            step = -2;
        }
        while (--length >= 0) {
            double x = src[srcOff + 0];
            double y = src[srcOff + 1];
            dst[dstOff + 0] = x * m00 + y * m01 + m02;
            dst[dstOff + 1] = x * m10 + y * m11 + m12;
            srcOff += step;
            dstOff += step;
        }
    }

    /**
     * Applies this AffineTransform to a set of points given as an array of
     * float values where every two values in the array give the coordinates of
     * a point; the even-indexed values giving the x coordinates and the
     * odd-indexed values giving the y coordinates.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     */
    public void transform(float[] src, int srcOff, float[] dst, int dstOff, int length) {
        int step = 2;
        if (src == dst && srcOff < dstOff && dstOff < srcOff + length * 2) {
            srcOff = srcOff + length * 2 - 2;
            dstOff = dstOff + length * 2 - 2;
            step = -2;
        }
        while (--length >= 0) {
            float x = src[srcOff + 0];
            float y = src[srcOff + 1];
            dst[dstOff + 0] = (float)(x * m00 + y * m01 + m02);
            dst[dstOff + 1] = (float)(x * m10 + y * m11 + m12);
            srcOff += step;
            dstOff += step;
        }
    }

    /**
     * Applies this AffineTransform to a set of points given as an array of
     * float values where every two values in the array give the coordinates of
     * a point; the even-indexed values giving the x coordinates and the
     * odd-indexed values giving the y coordinates. The destination coordinates
     * are given as values of type <code>double</code>.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     */
    public void transform(float[] src, int srcOff, double[] dst, int dstOff, int length) {
        while (--length >= 0) {
            float x = src[srcOff++];
            float y = src[srcOff++];
            dst[dstOff++] = x * m00 + y * m01 + m02;
            dst[dstOff++] = x * m10 + y * m11 + m12;
        }
    }

    /**
     * Applies this AffineTransform to a set of points given as an array of
     * double values where every two values in the array give the coordinates of
     * a point; the even-indexed values giving the x coordinates and the
     * odd-indexed values giving the y coordinates. The destination coordinates
     * are given as values of type <code>float</code>.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     */
    public void transform(double[] src, int srcOff, float[] dst, int dstOff, int length) {
        while (--length >= 0) {
            double x = src[srcOff++];
            double y = src[srcOff++];
            dst[dstOff++] = (float)(x * m00 + y * m01 + m02);
            dst[dstOff++] = (float)(x * m10 + y * m11 + m12);
        }
    }

    /**
     * Transforms the point according to the linear transformation part of this
     * AffineTransformation (without applying the translation).
     * 
     * @param src
     *            the original point.
     * @param dst
     *            the point object where the result of the delta transform is
     *            written.
     * @return the result of applying the delta transform (linear part only) to
     *         the original point.
     */
    // TODO: is this right? if dst is null, we check what it's an
    // instance of? Shouldn't it be src instanceof Point2D.Double?
    public Point2D deltaTransform(Point2D src, Point2D dst) {
        if (dst == null) {
            if (dst instanceof Point2D.Double) {
                dst = new Point2D.Double();
            } else {
                dst = new Point2D.Float();
            }
        }

        double x = src.getX();
        double y = src.getY();

        dst.setLocation(x * m00 + y * m01, x * m10 + y * m11);
        return dst;
    }

    /**
     * Applies the linear transformation part of this AffineTransform (ignoring
     * the translation part) to a set of points given as an array of double
     * values where every two values in the array give the coordinates of a
     * point; the even-indexed values giving the x coordinates and the
     * odd-indexed values giving the y coordinates.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the delta transformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     */
    public void deltaTransform(double[] src, int srcOff, double[] dst, int dstOff, int length) {
        while (--length >= 0) {
            double x = src[srcOff++];
            double y = src[srcOff++];
            dst[dstOff++] = x * m00 + y * m01;
            dst[dstOff++] = x * m10 + y * m11;
        }
    }

    /**
     * Transforms the point according to the inverse of this
     * AffineTransformation.
     * 
     * @param src
     *            the original point.
     * @param dst
     *            the point object where the result of the inverse transform is
     *            written (may be null).
     * @return the result of applying the inverse transform. Inverse transform.
     * @throws NoninvertibleTransformException
     *             if this AffineTransform cannot be inverted (the determinant
     *             of the linear transformation part is zero).
     */
    public Point2D inverseTransform(Point2D src, Point2D dst)
            throws NoninvertibleTransformException {
        double det = getDeterminant();
        if (Math.abs(det) < ZERO) {
            // awt.204=Determinant is zero
            throw new NoninvertibleTransformException(Messages.getString("awt.204")); //$NON-NLS-1$
        }

        if (dst == null) {
            if (src instanceof Point2D.Double) {
                dst = new Point2D.Double();
            } else {
                dst = new Point2D.Float();
            }
        }

        double x = src.getX() - m02;
        double y = src.getY() - m12;

        dst.setLocation((x * m11 - y * m01) / det, (y * m00 - x * m10) / det);
        return dst;
    }

    /**
     * Applies the inverse of this AffineTransform to a set of points given as
     * an array of double values where every two values in the array give the
     * coordinates of a point; the even-indexed values giving the x coordinates
     * and the odd-indexed values giving the y coordinates.
     * 
     * @param src
     *            the array of points to be transformed.
     * @param srcOff
     *            the offset in the source point array of the first point to be
     *            transformed.
     * @param dst
     *            the point array where the images of the points (after applying
     *            the inverse of the AffineTransformation) should be placed.
     * @param dstOff
     *            the offset in the destination array where the new values
     *            should be written.
     * @param length
     *            the number of points to transform.
     * @throws ArrayIndexOutOfBoundsException
     *             if <code>srcOff + length*2 > src.length</code> or
     *             <code>dstOff + length*2 > dst.length</code>.
     * @throws NoninvertibleTransformException
     *             if this AffineTransform cannot be inverted (the determinant
     *             of the linear transformation part is zero).
     */
    public void inverseTransform(double[] src, int srcOff, double[] dst, int dstOff, int length)
            throws NoninvertibleTransformException {
        double det = getDeterminant();
        if (Math.abs(det) < ZERO) {
            // awt.204=Determinant is zero
            throw new NoninvertibleTransformException(Messages.getString("awt.204")); //$NON-NLS-1$
        }

        while (--length >= 0) {
            double x = src[srcOff++] - m02;
            double y = src[srcOff++] - m12;
            dst[dstOff++] = (x * m11 - y * m01) / det;
            dst[dstOff++] = (y * m00 - x * m10) / det;
        }
    }

    /**
     * Creates a new shape whose data is given by applying this AffineTransform
     * to the specified shape.
     * 
     * @param src
     *            the original shape whose data is to be transformed.
     * @return the new shape found by applying this AffineTransform to the
     *         original shape.
     */
    public Shape createTransformedShape(Shape src) {
        if (src == null) {
            return null;
        }
        if (src instanceof GeneralPath) {
            return ((GeneralPath)src).createTransformedShape(this);
        }
        PathIterator path = src.getPathIterator(this);
        GeneralPath dst = new GeneralPath(path.getWindingRule());
        dst.append(path, false);
        return dst;
    }

    @Override
    public String toString() {
        return getClass().getName() + "[[" + m00 + ", " + m01 + ", " + m02 + "], [" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + m10 + ", " + m11 + ", " + m12 + "]]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }

    @Override
    public int hashCode() {
        HashCode hash = new HashCode();
        hash.append(m00);
        hash.append(m01);
        hash.append(m02);
        hash.append(m10);
        hash.append(m11);
        hash.append(m12);
        return hash.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof AffineTransform) {
            AffineTransform t = (AffineTransform)obj;
            return m00 == t.m00 && m01 == t.m01 && m02 == t.m02 && m10 == t.m10 && m11 == t.m11
                    && m12 == t.m12;
        }
        return false;
    }

    /**
     * Writes the AffineTrassform object to the output steam.
     * 
     * @param stream
     *            - the output stream.
     * @throws IOException
     *             - if there are I/O errors while writing to the output stream.
     */
    private void writeObject(java.io.ObjectOutputStream stream) throws IOException {
        stream.defaultWriteObject();
    }

    /**
     * Read the AffineTransform object from the input stream.
     * 
     * @param stream
     *            - the input stream.
     * @throws IOException
     *             - if there are I/O errors while reading from the input
     *             stream.
     * @throws ClassNotFoundException
     *             - if class could not be found.
     */
    private void readObject(java.io.ObjectInputStream stream) throws IOException,
            ClassNotFoundException {
        stream.defaultReadObject();
        type = TYPE_UNKNOWN;
    }

}

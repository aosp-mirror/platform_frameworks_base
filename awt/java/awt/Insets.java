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
 * @author Dmitry A. Durnev
 * @version $Revision$
 */

package java.awt;

import java.io.Serializable;

import org.apache.harmony.misc.HashCode;

/**
 * The Insets class represents the borders of a container. This class describes
 * the space that a container should leave at each edge: the top, the bottom,
 * the right side, and the left side. The space can be filled with a border, a
 * blank space, or a title.
 * 
 * @since Android 1.0
 */
public class Insets implements Cloneable, Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -2272572637695466749L;

    /**
     * The top inset indicates the size of the space added to the top of the
     * rectangle.
     */
    public int top;

    /**
     * The left inset indicates the size of the space added to the left side of
     * the rectangle.
     */
    public int left;

    /**
     * The bottom inset indicates the size of the space subtracted from the
     * bottom of the rectangle.
     */
    public int bottom;

    /**
     * The right inset indicates the size of the space subtracted from the right
     * side of the rectangle.
     */
    public int right;

    /**
     * Instantiates a new Inset object with the specified top, left, bottom,
     * right parameters.
     * 
     * @param top
     *            the top inset.
     * @param left
     *            the left inset.
     * @param bottom
     *            the bottom inset.
     * @param right
     *            the right inset.
     */
    public Insets(int top, int left, int bottom, int right) {
        setValues(top, left, bottom, right);
    }

    /**
     * Returns a hash code of the Insets object.
     * 
     * @return a hash code of the Insets object.
     */
    @Override
    public int hashCode() {
        int hashCode = HashCode.EMPTY_HASH_CODE;
        hashCode = HashCode.combine(hashCode, top);
        hashCode = HashCode.combine(hashCode, left);
        hashCode = HashCode.combine(hashCode, bottom);
        hashCode = HashCode.combine(hashCode, right);
        return hashCode;
    }

    /**
     * Returns a copy of this Insets object.
     * 
     * @return a copy of this Insets object.
     */
    @Override
    public Object clone() {
        return new Insets(top, left, bottom, right);
    }

    /**
     * Checks if this Insets object is equal to the specified object.
     * 
     * @param o
     *            the Object to be compared.
     * @return true, if the object is an Insets object whose data values are
     *         equal to those of this object, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof Insets) {
            Insets i = (Insets)o;
            return ((i.left == left) && (i.bottom == bottom) && (i.right == right) && (i.top == top));
        }
        return false;
    }

    /**
     * Returns a String representation of this Insets object.
     * 
     * @return a String representation of this Insets object.
     */
    @Override
    public String toString() {
        /*
         * The format is based on 1.5 release behavior which can be revealed by
         * the following code: System.out.println(new Insets(1, 2, 3, 4));
         */

        return (getClass().getName() + "[left=" + left + ",top=" + top + //$NON-NLS-1$ //$NON-NLS-2$
                ",right=" + right + ",bottom=" + bottom + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Sets top, left, bottom, and right insets to the specified values.
     * 
     * @param top
     *            the top inset.
     * @param left
     *            the left inset.
     * @param bottom
     *            the bottom inset.
     * @param right
     *            the right inset.
     */
    public void set(int top, int left, int bottom, int right) {
        setValues(top, left, bottom, right);
    }

    /**
     * Sets the values.
     * 
     * @param top
     *            the top.
     * @param left
     *            the left.
     * @param bottom
     *            the bottom.
     * @param right
     *            the right.
     */
    private void setValues(int top, int left, int bottom, int right) {
        this.top = top;
        this.left = left;
        this.bottom = bottom;
        this.right = right;
    }
}

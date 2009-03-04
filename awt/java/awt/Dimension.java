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

package java.awt;

import java.awt.geom.Dimension2D;
import java.io.Serializable;

import org.apache.harmony.misc.HashCode;

/**
 * The Dimension represents the size (width and height) of a component. The
 * width and height values can be negative, but in that case the behavior of
 * some methods is unexpected.
 * 
 * @since Android 1.0
 */
public class Dimension extends Dimension2D implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 4723952579491349524L;

    /**
     * The width dimension.
     */
    public int width;

    /**
     * The height dimension.
     */
    public int height;

    /**
     * Instantiates a new Dimension with the same data as the specified
     * Dimension.
     * 
     * @param d
     *            the Dimension to copy the data from when creating the new
     *            Dimension object.
     */
    public Dimension(Dimension d) {
        this(d.width, d.height);
    }

    /**
     * Instantiates a new Dimension with zero width and height.
     */
    public Dimension() {
        this(0, 0);
    }

    /**
     * Instantiates a new Dimension with the specified width and height.
     * 
     * @param width
     *            the width of the new Dimension.
     * @param height
     *            the height of the new Dimension.
     */
    public Dimension(int width, int height) {
        setSize(width, height);
    }

    /**
     * Returns the hash code of the Dimension.
     * 
     * @return the hash code of the Dimension.
     */
    @Override
    public int hashCode() {
        HashCode hash = new HashCode();
        hash.append(width);
        hash.append(height);
        return hash.hashCode();
    }

    /**
     * Compares this Dimension object with the specified object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified Object is a Dimension with the same width
     *         and height data as this Dimension.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof Dimension) {
            Dimension d = (Dimension)obj;
            return (d.width == width && d.height == height);
        }
        return false;
    }

    /**
     * Returns the String associated to this Dimension object.
     * 
     * @return the String associated to this Dimension object.
     */
    @Override
    public String toString() {
        // The output format based on 1.5 release behaviour. It could be
        // obtained in the following way
        // System.out.println(new Dimension().toString())
        return getClass().getName() + "[width=" + width + ",height=" + height + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Sets the size of this Dimension object with the specified width and
     * height.
     * 
     * @param width
     *            the width of the Dimension.
     * @param height
     *            the height of the Dimension.
     */
    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Sets the size of this Dimension object by copying the data from the
     * specified Dimension object.
     * 
     * @param d
     *            the Dimension that gives the new size values.
     */
    public void setSize(Dimension d) {
        setSize(d.width, d.height);
    }

    /**
     * Sets the size of this Dimension object with the specified double width
     * and height.
     * 
     * @param width
     *            the width of the Dimension.
     * @param height
     *            the height of the Dimension.
     * @see java.awt.geom.Dimension2D#setSize(double, double)
     */
    @Override
    public void setSize(double width, double height) {
        setSize((int)Math.ceil(width), (int)Math.ceil(height));
    }

    /**
     * Gets the size of the Dimension.
     * 
     * @return the size of the Dimension.
     */
    public Dimension getSize() {
        return new Dimension(width, height);
    }

    /**
     * Gets the height of the Dimension.
     * 
     * @return the height of the Dimension.
     * @see java.awt.geom.Dimension2D#getHeight()
     */
    @Override
    public double getHeight() {
        return height;
    }

    /**
     * Gets the width of the Dimension.
     * 
     * @return the width of the Dimension.
     * @see java.awt.geom.Dimension2D#getWidth()
     */
    @Override
    public double getWidth() {
        return width;
    }

}

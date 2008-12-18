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

/**
 * The Class Dimension2D represents a size (width and height) of a geometric
 * object. It stores double-valued data in order to be compatible with
 * high-precision geometric operations.
 * 
 * @since Android 1.0
 */
public abstract class Dimension2D implements Cloneable {

    /**
     * Instantiates a new dimension 2d with no data.
     */
    protected Dimension2D() {
    }

    /**
     * Gets the width.
     * 
     * @return the width.
     */
    public abstract double getWidth();

    /**
     * Gets the height.
     * 
     * @return the height.
     */
    public abstract double getHeight();

    /**
     * Sets the width and height.
     * 
     * @param width
     *            the width.
     * @param height
     *            the height.
     */
    public abstract void setSize(double width, double height);

    /**
     * Sets the width and height based on the data of another Dimension2D
     * object.
     * 
     * @param d
     *            the Dimension2D object providing the data to copy into this
     *            Dimension2D object.
     */
    public void setSize(Dimension2D d) {
        setSize(d.getWidth(), d.getHeight());
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError();
        }
    }
}

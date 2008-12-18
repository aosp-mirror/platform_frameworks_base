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

/**
 * The Stroke interface gives a pen style to be used by the Graphics2D
 * interface. It provides a means for getting a stroked version of a shape,
 * which is the version that is suitable for drawing via the Graphics2D
 * interface. Stroking a shape gives the shape's outline a width or drawing
 * style.
 * <p>
 * The Draw methods from Graphics2D interface should use the Stroke object for
 * rendering the shape's outline. The stroke should be set by
 * setStroke(java.awt.Stroke) method of the Graphics2D interface.
 * 
 * @see java.awt.Graphics2D#setStroke(java.awt.Stroke)
 * @since Android 1.0
 */
public interface Stroke {

    /**
     * Creates the stroked shape, which is the version that is suitable for
     * drawing via the Graphics2D interface. Stroking a shape gives the shape's
     * outline a width or drawing style.
     * 
     * @param p
     *            the original shape.
     * @return the stroked shape.
     */
    public Shape createStrokedShape(Shape p);
}

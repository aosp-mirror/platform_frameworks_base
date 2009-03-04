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

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.ColorModel;

/**
 * The Paint interface provides possibility of generating color patterns in
 * device space for fill, draw, or stroke operations in a Graphics2D.
 * 
 * @since Android 1.0
 */
public interface Paint extends Transparency {

    /**
     * Creates the PaintContext which is used to generate color patterns for
     * rendering operations of Graphics2D.
     * 
     * @param cm
     *            the ColorModel object, or null.
     * @param deviceBounds
     *            the Rectangle represents the bounding box of device space for
     *            the graphics rendering operations.
     * @param userBounds
     *            the Rectangle represents bounding box of user space for the
     *            graphics rendering operations.
     * @param xform
     *            the AffineTransform for translation from user space to device
     *            space.
     * @param hints
     *            the RenderingHints preferences.
     * @return the PaintContext for generating color patterns.
     */
    PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
            AffineTransform xform, RenderingHints hints);
}

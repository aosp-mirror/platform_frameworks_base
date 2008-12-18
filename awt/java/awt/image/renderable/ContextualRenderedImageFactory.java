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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt.image.renderable;

import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;

/**
 * A factory for creating ContextualRenderedImage objects with utilities for
 * manipulating the properties in the parameter block.
 * 
 * @since Android 1.0
 */
public interface ContextualRenderedImageFactory extends RenderedImageFactory {

    /**
     * Maps a render context to a parameter block and a renderable image.
     * 
     * @param a0
     *            the index.
     * @param a1
     *            the RenderContext.
     * @param a2
     *            the ParameterBlock.
     * @param a3
     *            the RenderableImage.
     * @return the render context.
     */
    public RenderContext mapRenderContext(int a0, RenderContext a1, ParameterBlock a2,
            RenderableImage a3);

    /**
     * Gets the value of the property from the parameter block.
     * 
     * @param a0
     *            the parameter block to examine to find the property.
     * @param a1
     *            the name of the property.
     * @return the value of the property.
     */
    public Object getProperty(ParameterBlock a0, String a1);

    /**
     * Creates the rendered image determined by the render context and parameter
     * block.
     * 
     * @param a0
     *            the RenderContext.
     * @param a1
     *            the ParameterBlock.
     * @return the rendered image.
     */
    public RenderedImage create(RenderContext a0, ParameterBlock a1);

    /**
     * Gets the bounding rectangle from the parameter block.
     * 
     * @param a0
     *            the parameter block to read the bounds from.
     * @return the bounding rectangle.
     */
    public Rectangle2D getBounds2D(ParameterBlock a0);

    /**
     * Gets the names of all of the supported properties.
     * 
     * @return the property names.
     */
    public String[] getPropertyNames();

    /**
     * Checks if this image factory is dynamic.
     * 
     * @return true, if this image factory is dynamic.
     */
    public boolean isDynamic();

}

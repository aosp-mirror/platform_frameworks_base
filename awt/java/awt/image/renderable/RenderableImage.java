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

import java.awt.RenderingHints;
import java.awt.image.RenderedImage;
import java.util.Vector;

/**
 * The Interface RenderableImage is implemented by an object that collects all
 * of the image-specific data that defines a single image that could be rendered
 * to different rendering targets.
 * 
 * @since Android 1.0
 */
public interface RenderableImage {

    /**
     * The Constant HINTS_OBSERVED indicates that the rendering hints are
     * applied rather than ignored.
     */
    public static final String HINTS_OBSERVED = "HINTS_OBSERVED"; //$NON-NLS-1$

    /**
     * Gets the property from the RenderableImage's parameter block.
     * 
     * @param name
     *            the name of the property to get.
     * @return the value of the property.
     */
    public Object getProperty(String name);

    /**
     * Creates the rendered image based on the information contained in the
     * parameters and the render context.
     * 
     * @param renderContext
     *            the render context giving rendering specifications such as
     *            transformations.
     * @return the rendered image.
     */
    public RenderedImage createRendering(RenderContext renderContext);

    /**
     * Creates the scaled rendered image based on the information contained in
     * the parameters and the render context.
     * 
     * @param w
     *            the desired width after scaling or zero if the scaling should
     *            be proportional, based on the height.
     * @param h
     *            the desired height after scaling or zero if the scaling should
     *            be proportional, based on the width.
     * @param hints
     *            the rendering hints to use.
     * @return the rendered image.
     * @throws IllegalArgumentException
     *             if both the height and width are zero.
     */
    public RenderedImage createScaledRendering(int w, int h, RenderingHints hints);

    /**
     * Gets the vector of sources from the parameter block.
     * 
     * @return the sources.
     */
    public Vector<RenderableImage> getSources();

    /**
     * Gets the names of all of the supported properties in the current context.
     * 
     * @return the property names.
     */
    public String[] getPropertyNames();

    /**
     * Creates the default rendering (using the identity transform and default
     * render context).
     * 
     * @return the rendered image.
     */
    public RenderedImage createDefaultRendering();

    /**
     * Checks if this context supports dynamic rendering.
     * 
     * @return true, if this context supports dynamic rendering.
     */
    public boolean isDynamic();

    /**
     * Gets the width of the image.
     * 
     * @return the width of the image.
     */
    public float getWidth();

    /**
     * Gets the y coordinate of the upper left corner.
     * 
     * @return the y coordinate of the upper left corner.
     */
    public float getMinY();

    /**
     * Gets the x coordinate of the upper left corner.
     * 
     * @return the x coordinate of the upper left corner.
     */
    public float getMinX();

    /**
     * Gets the height of the image.
     * 
     * @return the height of the image.
     */
    public float getHeight();

}

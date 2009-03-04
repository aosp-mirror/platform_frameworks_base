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
import java.awt.Shape;
import java.awt.geom.AffineTransform;

/**
 * The Class RenderContext stores data on how an image is to be rendered: the
 * affine transform, the area of interest, and the rendering hints.
 * 
 * @since Android 1.0
 */
public class RenderContext implements Cloneable {

    /**
     * The affine transform.
     */
    AffineTransform transform;

    /**
     * The area of interest.
     */
    Shape aoi;

    /**
     * The rendering hints.
     */
    RenderingHints hints;

    /**
     * Instantiates a new render context.
     * 
     * @param usr2dev
     *            the affine transform.
     * @param aoi
     *            the area of interest.
     * @param hints
     *            the rendering hints.
     */
    public RenderContext(AffineTransform usr2dev, Shape aoi, RenderingHints hints) {
        this.transform = (AffineTransform)usr2dev.clone();
        this.aoi = aoi;
        this.hints = hints;
    }

    /**
     * Instantiates a new render context with no specified hints.
     * 
     * @param usr2dev
     *            the affine transform.
     * @param aoi
     *            the area of interest.
     */
    public RenderContext(AffineTransform usr2dev, Shape aoi) {
        this(usr2dev, aoi, null);
    }

    /**
     * Instantiates a new render context with no specified area of interest.
     * 
     * @param usr2dev
     *            the affine transform.
     * @param hints
     *            the rendering hints.
     */
    public RenderContext(AffineTransform usr2dev, RenderingHints hints) {
        this(usr2dev, null, hints);
    }

    /**
     * Instantiates a new render context with no rendering hints or area of
     * interest.
     * 
     * @param usr2dev
     *            the affine transform.
     */
    public RenderContext(AffineTransform usr2dev) {
        this(usr2dev, null, null);
    }

    @Override
    public Object clone() {
        return new RenderContext(transform, aoi, hints);
    }

    /**
     * Sets the affine transform for this render context.
     * 
     * @param newTransform
     *            the new affine transform.
     */
    public void setTransform(AffineTransform newTransform) {
        transform = (AffineTransform)newTransform.clone();
    }

    /**
     * Concatenates the current transform with the specified transform (so they
     * are applied with the specified transform acting first) and sets the
     * resulting transform as the affine transform of this rendering context.
     * 
     * @param modTransform
     *            the new transform which modifies the current transform.
     * @deprecated use
     *             {@link RenderContext#preConcatenateTransform(AffineTransform)}
     *             .
     */
    @Deprecated
    public void preConcetenateTransform(AffineTransform modTransform) {
        preConcatenateTransform(modTransform);
    }

    /**
     * Concatenates the current transform with the specified transform (so they
     * are applied with the specified transform acting first) and sets the
     * resulting transform as the affine transform of this rendering context.
     * 
     * @param modTransform
     *            the new transform which modifies the current transform.
     */
    public void preConcatenateTransform(AffineTransform modTransform) {
        transform.preConcatenate(modTransform);
    }

    /**
     * Concatenate the specified transform with the current transform.
     * 
     * @param modTransform
     *            the new transform which modifies the current transform.
     * @deprecated use
     *             {@link RenderContext#concatenateTransform(AffineTransform)}.
     */
    @Deprecated
    public void concetenateTransform(AffineTransform modTransform) {
        concatenateTransform(modTransform);
    }

    /**
     * Concatenate the specified transform with the current transform.
     * 
     * @param modTransform
     *            the new transform which modifies the current transform.
     */
    public void concatenateTransform(AffineTransform modTransform) {
        transform.concatenate(modTransform);
    }

    /**
     * Gets the transform.
     * 
     * @return the transform.
     */
    public AffineTransform getTransform() {
        return (AffineTransform)transform.clone();
    }

    /**
     * Sets the area of interest.
     * 
     * @param newAoi
     *            the new area of interest.
     */
    public void setAreaOfInterest(Shape newAoi) {
        aoi = newAoi;
    }

    /**
     * Gets the area of interest.
     * 
     * @return the area of interest.
     */
    public Shape getAreaOfInterest() {
        return aoi;
    }

    /**
     * Sets the rendering hints.
     * 
     * @param hints
     *            the new rendering hints.
     */
    public void setRenderingHints(RenderingHints hints) {
        this.hints = hints;
    }

    /**
     * Gets the rendering hints.
     * 
     * @return the rendering hints.
     */
    public RenderingHints getRenderingHints() {
        return hints;
    }
}

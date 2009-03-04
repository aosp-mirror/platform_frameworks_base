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
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;
import java.util.Vector;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class RenderableImageOp is a basic implementation of RenderableImage,
 * with methods to access the parameter data and perform rendering operations.
 * 
 * @since Android 1.0
 */
public class RenderableImageOp implements RenderableImage {

    /**
     * The CRIF.
     */
    ContextualRenderedImageFactory CRIF;

    /**
     * The param block.
     */
    ParameterBlock paramBlock;

    /**
     * The height.
     */
    float minX, minY, width, height;

    /**
     * Instantiates a new renderable image op.
     * 
     * @param CRIF
     *            the cRIF.
     * @param paramBlock
     *            the param block.
     */
    public RenderableImageOp(ContextualRenderedImageFactory CRIF, ParameterBlock paramBlock) {
        this.CRIF = CRIF;
        this.paramBlock = (ParameterBlock)paramBlock.clone();
        Rectangle2D r = CRIF.getBounds2D(paramBlock);
        minX = (float)r.getMinX();
        minY = (float)r.getMinY();
        width = (float)r.getWidth();
        height = (float)r.getHeight();
    }

    public Object getProperty(String name) {
        return CRIF.getProperty(paramBlock, name);
    }

    /**
     * Sets the parameter block.
     * 
     * @param paramBlock
     *            the param block.
     * @return the parameter block.
     */
    public ParameterBlock setParameterBlock(ParameterBlock paramBlock) {
        ParameterBlock oldParam = this.paramBlock;
        this.paramBlock = (ParameterBlock)paramBlock.clone();
        return oldParam;
    }

    public RenderedImage createRendering(RenderContext renderContext) {

        Vector<RenderableImage> sources = getSources();
        ParameterBlock rdParam = (ParameterBlock)paramBlock.clone();

        if (sources != null) {
            Vector<Object> rdSources = new Vector<Object>();
            int i = 0;
            while (i < sources.size()) {
                RenderContext newContext = CRIF
                        .mapRenderContext(i, renderContext, paramBlock, this);
                RenderedImage rdim = sources.elementAt(i).createRendering(newContext);

                if (rdim != null) {
                    rdSources.addElement(rdim);
                }
                i++;
            }
            if (rdSources.size() > 0) {
                rdParam.setSources(rdSources);
            }
        }
        return CRIF.create(renderContext, rdParam);
    }

    public RenderedImage createScaledRendering(int w, int h, RenderingHints hints) {
        if (w == 0 && h == 0) {
            // awt.60=Width and Height mustn't be equal zero both
            throw new IllegalArgumentException(Messages.getString("awt.60")); //$NON-NLS-1$
        }
        if (w == 0) {
            w = Math.round(h * (getWidth() / getHeight()));
        }

        if (h == 0) {
            h = Math.round(w * (getHeight() / getWidth()));
        }

        double sx = (double)w / getWidth();
        double sy = (double)h / getHeight();

        AffineTransform at = AffineTransform.getScaleInstance(sx, sy);
        RenderContext context = new RenderContext(at, hints);
        return createRendering(context);
    }

    public Vector<RenderableImage> getSources() {
        if (paramBlock.getNumSources() == 0) {
            return null;
        }
        Vector<RenderableImage> v = new Vector<RenderableImage>();
        int i = 0;
        while (i < paramBlock.getNumSources()) {
            Object o = paramBlock.getSource(i);
            if (o instanceof RenderableImage) {
                v.addElement((RenderableImage)o);
            }
            i++;
        }
        return v;
    }

    public String[] getPropertyNames() {
        return CRIF.getPropertyNames();
    }

    /**
     * Gets the parameter block.
     * 
     * @return the parameter block
     */
    public ParameterBlock getParameterBlock() {
        return paramBlock;
    }

    public RenderedImage createDefaultRendering() {
        AffineTransform at = new AffineTransform();
        RenderContext context = new RenderContext(at);
        return createRendering(context);
    }

    public boolean isDynamic() {
        return CRIF.isDynamic();
    }

    public float getWidth() {
        return width;
    }

    public float getMinY() {
        return minY;
    }

    public float getMinX() {
        return minX;
    }

    public float getHeight() {
        return height;
    }

}

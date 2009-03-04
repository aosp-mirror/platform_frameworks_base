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
 * @author Ilya S. Okomin
 * @version $Revision$
 */
package java.awt.font;

import java.awt.geom.AffineTransform;

/**
 * The FontRenderContext class contains the information about text measurement.
 * Anti-aliasing and fractional-metrics modes are defined by an application and
 * affect the size of a character.
 * 
 * @since Android 1.0
 */
public class FontRenderContext {

    // Affine transform of this mode
    /**
     * The transform.
     */
    private AffineTransform transform;

    // Is the anti-aliased mode used
    /**
     * The anti aliased.
     */
    private boolean fAntiAliased;

    // Is the fractional metrics used
    /**
     * The fractional metrics.
     */
    private boolean fFractionalMetrics;


    /**
     * Instantiates a new FontRenderContext object with the specified
     * AffineTransform, anti-aliasing and fractional metrics flags.
     * 
     * @param trans
     *            the AffineTransform.
     * @param antiAliased
     *            the anti-aliasing flag.
     * @param usesFractionalMetrics
     *            the fractional metrics flag.
     */
    public FontRenderContext(AffineTransform trans, boolean antiAliased, 
            boolean usesFractionalMetrics) {
        if (trans != null){
            transform = new AffineTransform(trans);
        }
        fAntiAliased = antiAliased;
        fFractionalMetrics = usesFractionalMetrics;
    }

    /**
     * Instantiates a new FontRenderContext object.
     */
    protected FontRenderContext() {
    }

    /**
     * Compares the specified Object with current FontRenderContext object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified Object is equal to current
     *         FontRenderContext object.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj != null) {
            try {
                return equals((FontRenderContext) obj);
            } catch (ClassCastException e) {
                return false;
            }
        }
        return false;

    }

    /**
     * Gets the transform which is used for scaling typographical points to
     * pixels in this FontRenderContext.
     * 
     * @return the AffineTransform which is used for scaling typographical
     *         points to pixels in this FontRenderContext.
     */
    public AffineTransform getTransform() {
        if (transform != null){
            return new AffineTransform(transform);
        }
        return new AffineTransform();
    }

    /**
     * Compares the specified FontRenderContext object with current
     * FontRenderContext.
     * 
     * @param frc
     *            the FontRenderContext object to be compared.
     * @return true, if the specified FontRenderContext object is equal to
     *         current FontRenderContext.
     */
    public boolean equals(FontRenderContext frc) {
        if (this == frc){
            return true;
        }

        if (frc == null){
            return false;
        }

        if (!frc.getTransform().equals(this.getTransform()) &&
            !frc.isAntiAliased() == this.fAntiAliased &&
            !frc.usesFractionalMetrics() == this.fFractionalMetrics){
            return false;
        }
        return true;
    }

    /**
     * Returns true if the text fractional metrics are used in this
     * FontRenderContext.
     * 
     * @return true, if the text fractional metrics are used in this
     *         FontRenderContext, false otherwise.
     */
    public boolean usesFractionalMetrics() {
        return this.fFractionalMetrics;
    }

    /**
     * Returns true if anti-aliasing is used in this FontRenderContext.
     * 
     * @return true, if is anti-aliasing is used in this FontRenderContext,
     *         false otherwise.
     */
    public boolean isAntiAliased() {
        return this.fAntiAliased;
    }

    /**
     * Returns hash code of the FontRenderContext object.
     * 
     * @return the hash code of the FontRenderContext object.
     */
    @Override
    public int hashCode() {
        return this.getTransform().hashCode() ^
                new Boolean(this.fFractionalMetrics).hashCode() ^
                new Boolean(this.fAntiAliased).hashCode();
    }

}


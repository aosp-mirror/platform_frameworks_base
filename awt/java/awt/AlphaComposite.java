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

package java.awt;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;

import org.apache.harmony.awt.gl.ICompositeContext;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The AlphaComposite class defines a basic alpha compositing rules for
 * combining source and destination colors to achieve blending and transparency
 * effects with graphics and images.
 * 
 * @since Android 1.0
 */
public final class AlphaComposite implements Composite {

    /**
     * The Constant CLEAR indicates that both the color and the alpha of the
     * destination are cleared (Porter-Duff Clear rule).
     */
    public static final int CLEAR = 1;

    /**
     * The Constant SRC indicates that the source is copied to the destination
     * (Porter-Duff Source rule).
     */
    public static final int SRC = 2;

    /**
     * The Constant DST indicates that the destination is left untouched
     * (Porter-Duff Destination rule).
     */
    public static final int DST = 9;

    /**
     * The Constant SRC_OVER indicates that the source is composited over the
     * destination (Porter-Duff Source Over Destination rule).
     */
    public static final int SRC_OVER = 3;

    /**
     * The Constant DST_OVER indicates that The destination is composited over
     * the source and the result replaces the destination (Porter-Duff
     * Destination Over Source rule).
     */
    public static final int DST_OVER = 4;

    /**
     * The Constant SRC_IN indicates that the part of the source lying inside of
     * the destination replaces the destination (Porter-Duff Source In
     * Destination rule).
     */
    public static final int SRC_IN = 5;

    /**
     * The Constant DST_IN indicates that the part of the destination lying
     * inside of the source replaces the destination (Porter-Duff Destination In
     * Source rule).
     */
    public static final int DST_IN = 6;

    /**
     * The Constant SRC_OUT indicates that the part of the source lying outside
     * of the destination replaces the destination (Porter-Duff Source Held Out
     * By Destination rule).
     */
    public static final int SRC_OUT = 7;

    /**
     * The Constant DST_OUT indicates that the part of the destination lying
     * outside of the source replaces the destination (Porter-Duff Destination
     * Held Out By Source rule).
     */
    public static final int DST_OUT = 8;

    /**
     * The Constant SRC_ATOP indicates that the part of the source lying inside
     * of the destination is composited onto the destination (Porter-Duff Source
     * Atop Destination rule).
     */
    public static final int SRC_ATOP = 10;

    /**
     * The Constant DST_ATOP indicates that the part of the destination lying
     * inside of the source is composited over the source and replaces the
     * destination (Porter-Duff Destination Atop Source rule).
     */
    public static final int DST_ATOP = 11;

    /**
     * The Constant XOR indicates that the part of the source that lies outside
     * of the destination is combined with the part of the destination that lies
     * outside of the source (Porter-Duff Source Xor Destination rule).
     */
    public static final int XOR = 12;

    /**
     * AlphaComposite object with the opaque CLEAR rule and an alpha of 1.0f.
     */
    public static final AlphaComposite Clear = new AlphaComposite(CLEAR);

    /**
     * AlphaComposite object with the opaque SRC rule and an alpha of 1.0f.
     */
    public static final AlphaComposite Src = new AlphaComposite(SRC);

    /**
     * AlphaComposite object with the opaque DST rule and an alpha of 1.0f.
     */
    public static final AlphaComposite Dst = new AlphaComposite(DST);

    /**
     * AlphaComposite object with the opaque SRC_OVER rule and an alpha of 1.0f.
     */
    public static final AlphaComposite SrcOver = new AlphaComposite(SRC_OVER);

    /**
     * AlphaComposite object with the opaque DST_OVER rule and an alpha of 1.0f.
     */
    public static final AlphaComposite DstOver = new AlphaComposite(DST_OVER);

    /**
     * AlphaComposite object with the opaque SRC_IN rule and an alpha of 1.0f.
     */
    public static final AlphaComposite SrcIn = new AlphaComposite(SRC_IN);

    /**
     * AlphaComposite object with the opaque DST_IN rule and an alpha of 1.0f.
     */
    public static final AlphaComposite DstIn = new AlphaComposite(DST_IN);

    /**
     * AlphaComposite object with the opaque SRC_OUT rule and an alpha of 1.0f.
     */
    public static final AlphaComposite SrcOut = new AlphaComposite(SRC_OUT);

    /**
     * AlphaComposite object with the opaque DST_OUT rule and an alpha of 1.0f.
     */
    public static final AlphaComposite DstOut = new AlphaComposite(DST_OUT);

    /**
     * AlphaComposite object with the opaque SRC_ATOP rule and an alpha of 1.0f.
     */
    public static final AlphaComposite SrcAtop = new AlphaComposite(SRC_ATOP);

    /**
     * AlphaComposite object with the opaque DST_ATOP rule and an alpha of 1.0f.
     */
    public static final AlphaComposite DstAtop = new AlphaComposite(DST_ATOP);

    /**
     * AlphaComposite object with the opaque XOR rule and an alpha of 1.0f.
     */
    public static final AlphaComposite Xor = new AlphaComposite(XOR);

    /**
     * The rule.
     */
    private int rule;

    /**
     * The alpha.
     */
    private float alpha;

    /**
     * Instantiates a new alpha composite. Creates a context for the compositing
     * operation. The context contains state that is used in performing the
     * compositing operation.
     * 
     * @param rule
     *            the rule.
     * @param alpha
     *            the alpha.
     */
    private AlphaComposite(int rule, float alpha) {
        if (rule < CLEAR || rule > XOR) {
            // awt.11D=Unknown rule
            throw new IllegalArgumentException(Messages.getString("awt.11D")); //$NON-NLS-1$
        }
        if (alpha < 0.0f || alpha > 1.0f) {
            // awt.11E=Wrong alpha value
            throw new IllegalArgumentException(Messages.getString("awt.11E")); //$NON-NLS-1$
        }

        this.rule = rule;
        this.alpha = alpha;
    }

    /**
     * Instantiates a new alpha composite.
     * 
     * @param rule
     *            the rule.
     */
    private AlphaComposite(int rule) {
        this(rule, 1.0f);
    }

    /**
     * Creates a CompositeContext object with the specified source ColorModel,
     * destination ColorModel and RenderingHints parameters for a composing
     * operation.
     * 
     * @param srcColorModel
     *            the source's ColorModel.
     * @param dstColorModel
     *            the destination's ColorModel.
     * @param hints
     *            the RenderingHints object.
     * @return the CompositeContext object.
     * @see java.awt.Composite#createContext(java.awt.image.ColorModel,
     *      java.awt.image.ColorModel, java.awt.RenderingHints)
     */
    public CompositeContext createContext(ColorModel srcColorModel, ColorModel dstColorModel,
            RenderingHints hints) {
        return new ICompositeContext(this, srcColorModel, dstColorModel);
    }

    /**
     * Compares the AlphaComposite object with the specified object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the AlphaComposite object is equal to the specified
     *         object.
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof AlphaComposite)) {
            return false;
        }
        AlphaComposite other = (AlphaComposite)obj;
        return (this.rule == other.getRule() && this.alpha == other.getAlpha());
    }

    /**
     * Returns the hash code of the AlphaComposite object.
     * 
     * @return the hash code of the AlphaComposite object.
     */
    @Override
    public int hashCode() {
        int hash = Float.floatToIntBits(alpha);
        int tmp = hash >>> 24;
        hash <<= 8;
        hash |= tmp;
        hash ^= rule;
        return hash;
    }

    /**
     * Gets the compositing rule of this AlphaComposite object.
     * 
     * @return the compositing rule of this AlphaComposite object.
     */
    public int getRule() {
        return rule;
    }

    /**
     * Gets the alpha value of this AlphaComposite object; returns 1.0 if this
     * AlphaComposite object doesn't have alpha value.
     * 
     * @return the alpha value of this AlphaComposite object or 1.0 if this
     *         AlphaComposite object doesn't have alpha value.
     */
    public float getAlpha() {
        return alpha;
    }

    /**
     * Gets the AlphaComposite instance with the specified rule and alpha value.
     * 
     * @param rule
     *            the compositing rule.
     * @param alpha
     *            the alpha value.
     * @return the AlphaComposite instance.
     */
    public static AlphaComposite getInstance(int rule, float alpha) {
        if (alpha == 1.0f) {
            return getInstance(rule);
        }
        return new AlphaComposite(rule, alpha);
    }

    /**
     * Gets the AlphaComposite instance with the specified rule.
     * 
     * @param rule
     *            the compositing rule.
     * @return the AlphaComposite instance.
     */
    public static AlphaComposite getInstance(int rule) {
        switch (rule) {
            case CLEAR:
                return Clear;
            case SRC:
                return Src;
            case DST:
                return Dst;
            case SRC_OVER:
                return SrcOver;
            case DST_OVER:
                return DstOver;
            case SRC_IN:
                return SrcIn;
            case DST_IN:
                return DstIn;
            case SRC_OUT:
                return SrcOut;
            case DST_OUT:
                return DstOut;
            case SRC_ATOP:
                return SrcAtop;
            case DST_ATOP:
                return DstAtop;
            case XOR:
                return Xor;
            default:
                // awt.11D=Unknown rule
                throw new IllegalArgumentException(Messages.getString("awt.11D")); //$NON-NLS-1$
        }
    }

}

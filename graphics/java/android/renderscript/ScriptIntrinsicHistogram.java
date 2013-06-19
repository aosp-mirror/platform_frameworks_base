/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.renderscript;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

/**
 * Intrinsic Histogram filter.
 *
 *
 **/
public final class ScriptIntrinsicHistogram extends ScriptIntrinsic {
    private Allocation mOut;

    private ScriptIntrinsicHistogram(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Create an intrinsic for calculating the histogram of an uchar
     * or uchar4 image.
     *
     * Supported elements types are {@link Element#U8_4, @link
     * Element#U8}
     *
     * @param rs The RenderScript context
     * @param e Element type for inputs and outputs
     *
     * @return ScriptIntrinsicHistogram
     */
    public static ScriptIntrinsicHistogram create(RenderScript rs, Element e) {
        if ((!e.isCompatible(Element.U8_4(rs))) && (!e.isCompatible(Element.U8(rs)))) {
            throw new RSIllegalArgumentException("Unsuported element type.");
        }
        int id = rs.nScriptIntrinsicCreate(9, e.getID(rs));
        ScriptIntrinsicHistogram sib = new ScriptIntrinsicHistogram(id, rs);
        return sib;
    }

    public void forEach(Allocation ain) {
        if (ain.getType().getElement().getVectorSize() <
            mOut.getType().getElement().getVectorSize()) {

            throw new RSIllegalArgumentException(
                "Input vector sizse must be >= output vector size.");
        }
        if (ain.getType().getElement().isCompatible(Element.U8(mRS)) &&
            ain.getType().getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Output type must be U32 or I32.");
        }

        forEach(0, ain, null, null);
    }

    public void setDotCoefficients(float r, float g, float b, float a) {
        if ((r < 0.f) || (g < 0.f) || (b < 0.f) || (a < 0.f)) {
            throw new RSIllegalArgumentException("Coefficient may not be negative.");
        }
        if ((r + g + b + a) > 1.f) {
            throw new RSIllegalArgumentException("Sum of coefficients must be 1.0 or less.");
        }

        FieldPacker fp = new FieldPacker(16);
        fp.addF32(r);
        fp.addF32(g);
        fp.addF32(b);
        fp.addF32(a);
        setVar(0, fp);
    }

    /**
     * Set the output of the histogram.
     *
     * @param ain The input allocation
     */
    public void setOutput(Allocation aout) {
        mOut = aout;
        if (mOut.getType().getElement() != Element.U32(mRS) &&
            mOut.getType().getElement() != Element.U32_2(mRS) &&
            mOut.getType().getElement() != Element.U32_3(mRS) &&
            mOut.getType().getElement() != Element.U32_4(mRS) &&
            mOut.getType().getElement() != Element.I32(mRS) &&
            mOut.getType().getElement() != Element.I32_2(mRS) &&
            mOut.getType().getElement() != Element.I32_3(mRS) &&
            mOut.getType().getElement() != Element.I32_4(mRS)) {

            throw new RSIllegalArgumentException("Output type must be U32 or I32.");
        }
        if ((mOut.getType().getX() != 256) ||
            (mOut.getType().getY() != 0) ||
            mOut.getType().hasMipmaps() ||
            (mOut.getType().getYuv() != 0)) {

            throw new RSIllegalArgumentException("Output must be 1D, 256 elements.");
        }
        setVar(1, aout);
    }

    public void forEach_dot(Allocation ain) {
        if (mOut.getType().getElement().getVectorSize() != 1) {
            throw new RSIllegalArgumentException("Output vector size must be one.");
        }
        if (ain.getType().getElement().isCompatible(Element.U8(mRS)) &&
            ain.getType().getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Output type must be U32 or I32.");
        }

        forEach(1, ain, null, null);
    }



    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID_seperate() {
        return createKernelID(0, 3, null, null);
    }

    /**
     * Get a FieldID for the input field of this intrinsic.
     *
     * @return Script.FieldID The FieldID object.
     */
    public Script.FieldID getFieldID_Input() {
        return createFieldID(1, null);
    }
}


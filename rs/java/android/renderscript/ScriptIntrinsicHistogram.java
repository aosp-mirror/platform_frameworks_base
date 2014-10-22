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

/**
 * Intrinsic Histogram filter.
 *
 *
 **/
public final class ScriptIntrinsicHistogram extends ScriptIntrinsic {
    private Allocation mOut;

    private ScriptIntrinsicHistogram(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Create an intrinsic for calculating the histogram of an uchar
     * or uchar4 image.
     *
     * Supported elements types are
     * {@link Element#U8_4}, {@link Element#U8_3},
     * {@link Element#U8_2}, {@link Element#U8}
     *
     * @param rs The RenderScript context
     * @param e Element type for inputs
     *
     * @return ScriptIntrinsicHistogram
     */
    public static ScriptIntrinsicHistogram create(RenderScript rs, Element e) {
        if ((!e.isCompatible(Element.U8_4(rs))) &&
            (!e.isCompatible(Element.U8_3(rs))) &&
            (!e.isCompatible(Element.U8_2(rs))) &&
            (!e.isCompatible(Element.U8(rs)))) {
            throw new RSIllegalArgumentException("Unsuported element type.");
        }
        long id = rs.nScriptIntrinsicCreate(9, e.getID(rs));
        ScriptIntrinsicHistogram sib = new ScriptIntrinsicHistogram(id, rs);
        return sib;
    }

    /**
     * Process an input buffer and place the histogram into the
     * output allocation. The output allocation may be a narrower
     * vector size than the input. In this case the vector size of
     * the output is used to determine how many of the input
     * channels are used in the computation. This is useful if you
     * have an RGBA input buffer but only want the histogram for
     * RGB.
     *
     * 1D and 2D input allocations are supported.
     *
     * @param ain The input image
     */
    public void forEach(Allocation ain) {
        forEach(ain, null);
    }

    /**
     * Process an input buffer and place the histogram into the
     * output allocation. The output allocation may be a narrower
     * vector size than the input. In this case the vector size of
     * the output is used to determine how many of the input
     * channels are used in the computation. This is useful if you
     * have an RGBA input buffer but only want the histogram for
     * RGB.
     *
     * 1D and 2D input allocations are supported.
     *
     * @param ain The input image
     * @param opt LaunchOptions for clipping
     */
    public void forEach(Allocation ain, Script.LaunchOptions opt) {
        if (ain.getType().getElement().getVectorSize() <
            mOut.getType().getElement().getVectorSize()) {

            throw new RSIllegalArgumentException(
                "Input vector size must be >= output vector size.");
        }
        if (ain.getType().getElement().isCompatible(Element.U8(mRS)) &&
            ain.getType().getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Output type must be U32 or I32.");
        }

        forEach(0, ain, null, null, opt);
    }



    /**
     * Set the coefficients used for the RGBA to Luminocity
     * calculation. The default is {0.299f, 0.587f, 0.114f, 0.f}.
     *
     * Coefficients must be >= 0 and sum to 1.0 or less.
     *
     * @param r Red coefficient
     * @param g Green coefficient
     * @param b Blue coefficient
     * @param a Alpha coefficient
     */
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
     * Set the output of the histogram.  32 bit integer types are
     * supported.
     *
     * @param aout The output allocation
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


    /**
     * Process an input buffer and place the histogram into the
     * output allocation. The dot product of the input channel and
     * the coefficients from 'setDotCoefficients' are used to
     * calculate the output values.
     *
     * 1D and 2D input allocations are supported.
     *
     * @param ain The input image
     */
    public void forEach_Dot(Allocation ain) {
        forEach_Dot(ain, null);
    }

    /**
     * Process an input buffer and place the histogram into the
     * output allocation. The dot product of the input channel and
     * the coefficients from 'setDotCoefficients' are used to
     * calculate the output values.
     *
     * 1D and 2D input allocations are supported.
     *
     * @param ain The input image
     * @param opt LaunchOptions for clipping
     */
    public void forEach_Dot(Allocation ain, Script.LaunchOptions opt) {
        if (mOut.getType().getElement().getVectorSize() != 1) {
            throw new RSIllegalArgumentException("Output vector size must be one.");
        }
        if (ain.getType().getElement().isCompatible(Element.U8(mRS)) &&
            ain.getType().getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Output type must be U32 or I32.");
        }

        forEach(1, ain, null, null, opt);
    }



    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID_Separate() {
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


/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * Intrinsic for applying a 3x3 convolve to an allocation.
 *
 **/
public final class ScriptIntrinsicConvolve3x3 extends ScriptIntrinsic {
    private final float[] mValues = new float[9];
    private Allocation mInput;

    private ScriptIntrinsicConvolve3x3(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are {@link Element#U8_4}
     *
     * The default coefficients are.
     *
     * <code>
     * <p> [ 0,  0,  0 ]
     * <p> [ 0,  1,  0 ]
     * <p> [ 0,  0,  0 ]
     * </code>
     *
     * @param rs The Renderscript context
     * @param e Element type for intputs and outputs
     *
     * @return ScriptIntrinsicConvolve3x3
     */
    public static ScriptIntrinsicConvolve3x3 create(RenderScript rs, Element e) {
        float f[] = { 0, 0, 0, 0, 1, 0, 0, 0, 0};
        if (e != Element.U8_4(rs)) {
            throw new RSIllegalArgumentException("Unsuported element type.");
        }
        int id = rs.nScriptIntrinsicCreate(1, e.getID(rs));
        ScriptIntrinsicConvolve3x3 si = new ScriptIntrinsicConvolve3x3(id, rs);
        si.setCoefficients(f);
        return si;

    }

    /**
     * Set the input of the blur.
     * Must match the element type supplied during create.
     *
     * @param ain The input allocation.
     */
    public void setInput(Allocation ain) {
        mInput = ain;
        setVar(1, ain);
    }

    /**
     * Set the coefficients for the convolve.
     *
     * The convolve layout is
     * <code>
     * <p> [ 0,  1,  2 ]
     * <p> [ 3,  4,  5 ]
     * <p> [ 6,  7,  8 ]
     * </code>
     *
     * @param v The array of coefficients to set
     */
    public void setCoefficients(float v[]) {
        FieldPacker fp = new FieldPacker(9*4);
        for (int ct=0; ct < mValues.length; ct++) {
            mValues[ct] = v[ct];
            fp.addF32(mValues[ct]);
        }
        setVar(0, fp);
    }

    /**
     * Apply the filter to the input and save to the specified
     * allocation.
     *
     * @param aout Output allocation. Must match creation element
     *             type.
     */
    public void forEach(Allocation aout) {
        forEach(0, null, aout, null);
    }

    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID() {
        return createKernelID(0, 2, null, null);
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


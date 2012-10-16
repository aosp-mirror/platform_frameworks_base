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
 * Intrinsic Gausian blur filter. Applies a gaussian blur of the
 * specified radius to all elements of an allocation.
 *
 *
 **/
public final class ScriptIntrinsicBlur extends ScriptIntrinsic {
    private final float[] mValues = new float[9];
    private Allocation mInput;

    private ScriptIntrinsicBlur(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Create an intrinsic for applying a blur to an allocation. The
     * default radius is 5.0.
     *
     * Supported elements types are {@link Element#U8_4}
     *
     * @param rs The Renderscript context
     * @param e Element type for inputs and outputs
     *
     * @return ScriptIntrinsicBlur
     */
    public static ScriptIntrinsicBlur create(RenderScript rs, Element e) {
        if (e != Element.U8_4(rs)) {
            throw new RSIllegalArgumentException("Unsuported element type.");
        }
        int id = rs.nScriptIntrinsicCreate(5, e.getID(rs));
        ScriptIntrinsicBlur sib = new ScriptIntrinsicBlur(id, rs);
        sib.setRadius(5.f);
        return sib;
    }

    /**
     * Set the input of the blur.
     * Must match the element type supplied during create.
     *
     * @param ain The input allocation
     */
    public void setInput(Allocation ain) {
        mInput = ain;
        setVar(1, ain);
    }

    /**
     * Set the radius of the Blur.
     *
     * Supported range 0 < radius <= 25
     *
     * @param radius The radius of the blur
     */
    public void setRadius(float radius) {
        if (radius <= 0 || radius > 25) {
            throw new RSIllegalArgumentException("Radius out of range (0 < r <= 25).");
        }
        setVar(0, radius);
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


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

import android.util.Log;

/**
 * @hide
 **/
public class ScriptIntrinsicConvolve5x5 extends ScriptIntrinsic {
    private float[] mValues = new float[25];
    private Allocation mInput;

    ScriptIntrinsicConvolve5x5(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are float, float4, uchar, uchar4
     *
     *
     * @param rs
     * @param e
     *
     * @return ScriptIntrinsicConvolve5x5
     */
    public static ScriptIntrinsicConvolve5x5 create(RenderScript rs, Element e) {
        int id = rs.nScriptIntrinsicCreate(4, e.getID(rs));
        return new ScriptIntrinsicConvolve5x5(id, rs);

    }

    public void setInput(Allocation ain) {
        mInput = ain;
        bindAllocation(ain, 1);
    }

    public void setCoefficients(float v[]) {
        FieldPacker fp = new FieldPacker(25*4);
        for (int ct=0; ct < mValues.length; ct++) {
            mValues[ct] = v[ct];
            fp.addF32(mValues[ct]);
        }
        setVar(0, fp);
    }

    public void forEach(Allocation aout) {
        forEach(0, null, aout, null);
    }

}


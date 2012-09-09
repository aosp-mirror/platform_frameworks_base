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
 * @hide
 **/
public class ScriptIntrinsicBlur extends ScriptIntrinsic {
    private float[] mValues = new float[9];
    private Allocation mInput;

    ScriptIntrinsicBlur(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are float, float4, uchar, uchar4
     *
     *
     * @param rs
     * @param e
     *
     * @return ScriptIntrinsicConvolve3x3
     */
    public static ScriptIntrinsicBlur create(RenderScript rs, Element e) {
        int id = rs.nScriptIntrinsicCreate(5, e.getID(rs));
        return new ScriptIntrinsicBlur(id, rs);

    }

    public void setInput(Allocation ain) {
        mInput = ain;
        bindAllocation(ain, 1);
    }

    public void setRadius(float v) {
        if (v < 0 || v > 25) {
            throw new RSIllegalArgumentException("Radius out of range (0-25).");
        }
        setVar(0, v);
    }

    public void forEach(Allocation aout) {
        forEach(0, null, aout, null);
    }

}


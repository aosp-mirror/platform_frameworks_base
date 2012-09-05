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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map.Entry;
import java.util.HashMap;


/**
 * @hide
 **/
public class ScriptIntrinsicColorMatrix extends ScriptIntrinsic {
    private Matrix4f mMatrix = new Matrix4f();
    private Allocation mInput;

    ScriptIntrinsicColorMatrix(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are float, float4, uchar, uchar4
     *
     *
     * @param rs
     * @param e
     *
     * @return ScriptIntrinsicColorMatrix
     */
    public static ScriptIntrinsicColorMatrix create(RenderScript rs, Element e) {
        int id = rs.nScriptIntrinsicCreate(2, e.getID(rs));
        return new ScriptIntrinsicColorMatrix(id, rs);

    }

    public void setColorMatrix(Matrix4f m) {
        mMatrix.load(m);
        FieldPacker fp = new FieldPacker(16*4);
        fp.addMatrix(m);
        setVar(0, fp);
    }

    public void forEach(Allocation ain, Allocation aout) {
        forEach(0, ain, aout, null);
    }

}


/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.rs.test;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;


public class RSTestCore {
    public static final int PART_COUNT = 50000;

    public RSTestCore() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private ScriptC_Test_root mRootScript;

    private boolean fp_mad() {
        ScriptC_Fp_mad s = new ScriptC_Fp_mad(mRS, mRes, R.raw.fp_mad, true);
        s.invoke_doTest(0, 0);
        return true;
    }

    //private ScriptC_Fountain mScript;
    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;

        mRootScript = new ScriptC_Test_root(mRS, mRes, R.raw.test_root, true);

        fp_mad();


        /*
        ProgramFragment.Builder pfb = new ProgramFragment.Builder(rs);
        pfb.setVaryingColor(true);
        rs.contextBindProgramFragment(pfb.create());

        ScriptField_Point points = new ScriptField_Point(mRS, PART_COUNT);

        Mesh.AllocationBuilder smb = new Mesh.AllocationBuilder(mRS);
        smb.addVertexAllocation(points.getAllocation());
        smb.addIndexType(Primitive.POINT);
        Mesh sm = smb.create();

        mScript = new ScriptC_Fountain(mRS, mRes, R.raw.fountain, true);
        mScript.set_partMesh(sm);
        mScript.bind_point(points);
        mRS.contextBindRootScript(mScript);
        */
    }

    public void newTouchPosition(float x, float y, float pressure, int id) {
    }
}

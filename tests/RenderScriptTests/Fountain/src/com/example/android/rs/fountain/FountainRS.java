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

package com.example.android.rs.fountain;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;


public class FountainRS {
    public static final int PART_COUNT = 50000;

    public FountainRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_fountain mScript;
    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;

        ProgramFragmentFixedFunction.Builder pfb = new ProgramFragmentFixedFunction.Builder(rs);
        pfb.setVaryingColor(true);
        rs.bindProgramFragment(pfb.create());

        ScriptField_Point points = new ScriptField_Point(mRS, PART_COUNT);//
 //                                                        Allocation.USAGE_GRAPHICS_VERTEX);

        Mesh.AllocationBuilder smb = new Mesh.AllocationBuilder(mRS);
        smb.addVertexAllocation(points.getAllocation());
        smb.addIndexSetType(Mesh.Primitive.POINT);
        Mesh sm = smb.create();

        mScript = new ScriptC_fountain(mRS, mRes, R.raw.fountain);
        mScript.set_partMesh(sm);
        mScript.bind_point(points);
        mRS.bindRootScript(mScript);
    }

    boolean holdingColor[] = new boolean[10];
    public void newTouchPosition(float x, float y, float pressure, int id) {
        if (id >= holdingColor.length) {
            return;
        }
        int rate = (int)(pressure * pressure * 500.f);
        if (rate > 500) {
            rate = 500;
        }
        if (rate > 0) {
            mScript.invoke_addParticles(rate, x, y, id, !holdingColor[id]);
            holdingColor[id] = true;
        } else {
            holdingColor[id] = false;
        }

    }
}

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

package com.android.fountain;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;


public class FountainRS {
    public static final int PART_COUNT = 20000;

    public FountainRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_Fountain mScript;
    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;

        ScriptField_Point points = new ScriptField_Point(mRS, PART_COUNT);

        SimpleMesh.Builder smb = new SimpleMesh.Builder(mRS);
        int vtxSlot = smb.addVertexType(points.getType());
        smb.setPrimitive(Primitive.POINT);
        SimpleMesh sm = smb.create();
        sm.bindVertexAllocation(points.getAllocation(), vtxSlot);

        mScript = new ScriptC_Fountain(mRS, mRes, R.raw.fountain_bc, true);
        mScript.set_partMesh(sm);
        mScript.bind_point(points);
        mRS.contextBindRootScript(mScript);
    }

    boolean holdingColor = false;
    public void newTouchPosition(int x, int y, int rate) {
        if (rate > 0) {
            mScript.invoke_addParticles(rate, x, y, !holdingColor ? 1 : 0);
            holdingColor = true;
        } else {
            holdingColor = false;
        }

    }
}



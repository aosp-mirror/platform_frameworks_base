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

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    Float4 tmpColor = new Float4();
    boolean holdingColor = false;
    public void newTouchPosition(int x, int y, int rate) {
        if (rate > 0) {
            if (true/*!holdingColor*/) {
                tmpColor.x = ((x & 0x1) != 0) ? 0.f : 1.f;
                tmpColor.y = ((x & 0x2) != 0) ? 0.f : 1.f;
                tmpColor.z = ((x & 0x4) != 0) ? 0.f : 1.f;
                if ((tmpColor.x + tmpColor.y + tmpColor.z) < 0.9f) {
                    tmpColor.x = 0.8f;
                    tmpColor.y = 0.5f;
                    tmpColor.z = 1.0f;
                }
                android.util.Log.e("rs", "set color " + tmpColor.x + ", " + tmpColor.y + ", " + tmpColor.z);
                mScript.set_partColor(tmpColor);
            }
            mScript.invokable_addParticles(rate, x, y);
            holdingColor = true;
        } else {
            holdingColor = false;
        }

    }


    /////////////////////////////////////////

    private Resources mRes;

    private ScriptField_Point mPoints;
    private ScriptC_Fountain mScript;
    private RenderScriptGL mRS;
    private SimpleMesh mSM;

    private void initRS() {
        mPoints = new ScriptField_Point(mRS, PART_COUNT);

        SimpleMesh.Builder smb = new SimpleMesh.Builder(mRS);
        int vtxSlot = smb.addVertexType(mPoints.getType());
        smb.setPrimitive(Primitive.POINT);
        mSM = smb.create();
        mSM.bindVertexAllocation(mPoints.getAllocation(), vtxSlot);

        mScript = new ScriptC_Fountain(mRS, mRes, true);
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.set_partMesh(mSM);
        mScript.set_partBuffer(mPoints.getAllocation());
        mScript.bind_point(mPoints);
        mRS.contextBindRootScript(mScript);
    }

}



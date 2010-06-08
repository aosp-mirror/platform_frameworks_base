/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.renderscript.*;
import android.content.res.Resources;
import android.util.Log;

public class ScriptC_Fountain extends ScriptC {
    // Constructor
    public  ScriptC_Fountain(RenderScript rs, Resources resources, boolean isRoot) {
        super(rs, resources, R.raw.fountain_bc, isRoot);
    }

    private final static int mExportVarIdx_partColor = 0;
    private Float4 mExportVar_partColor;
    public void set_partColor(Float4 v) {
        mExportVar_partColor = v;
        FieldPacker fp = new FieldPacker(16);
        fp.addF32(v);
        setVar(mExportVarIdx_partColor, fp);
    }

    public Float4 get_partColor() {
        return mExportVar_partColor;
    }

    private final static int mExportVarIdx_partMesh = 1;
    private SimpleMesh mExportVar_partMesh;
    public void set_partMesh(SimpleMesh v) {
        mExportVar_partMesh = v;
        int id = 0;
        if (v != null) id = v.getID();
        setVar(mExportVarIdx_partMesh, id);
    }

    public SimpleMesh get_partMesh() {
        return mExportVar_partMesh;
    }

    private final static int mExportVarIdx_point = 2;
    private ScriptField_Point_s mExportVar_point;
    public void bind_point(ScriptField_Point_s v) {
        mExportVar_point = v;
        if(v == null) bindAllocation(null, mExportVarIdx_point);
        else bindAllocation(v.getAllocation(), mExportVarIdx_point);
    }

    public ScriptField_Point_s get_point() {
        return mExportVar_point;
    }

    private final static int mExportFuncIdx_addParticles = 0;
    public void invoke_addParticles(int rate, int x, int y) {
        FieldPacker addParticles_fp = new FieldPacker(12);
        addParticles_fp.addI32(rate);
        addParticles_fp.addI32(x);
        addParticles_fp.addI32(y);
        invokeV(mExportFuncIdx_addParticles, addParticles_fp);
    }

}


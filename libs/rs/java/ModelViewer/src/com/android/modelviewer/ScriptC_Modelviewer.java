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

package com.android.modelviewer;

import android.renderscript.*;
import android.content.res.Resources;
import android.util.Log;

public class ScriptC_Modelviewer extends ScriptC {
    // Constructor
    public  ScriptC_Modelviewer(RenderScript rs, Resources resources, int id, boolean isRoot) {
        super(rs, resources, id, isRoot);
    }

    private final static int mExportVarIdx_gPVBackground = 0;
    private ProgramVertex mExportVar_gPVBackground;
    public void set_gPVBackground(ProgramVertex v) {
        mExportVar_gPVBackground = v;
        setVar(mExportVarIdx_gPVBackground, (v == null) ? 0 : v.getID());
    }

    public ProgramVertex get_gPVBackground() {
        return mExportVar_gPVBackground;
    }

    private final static int mExportVarIdx_gPFBackground = 1;
    private ProgramFragment mExportVar_gPFBackground;
    public void set_gPFBackground(ProgramFragment v) {
        mExportVar_gPFBackground = v;
        setVar(mExportVarIdx_gPFBackground, (v == null) ? 0 : v.getID());
    }

    public ProgramFragment get_gPFBackground() {
        return mExportVar_gPFBackground;
    }

    private final static int mExportVarIdx_gTGrid = 2;
    private Allocation mExportVar_gTGrid;
    public void set_gTGrid(Allocation v) {
        mExportVar_gTGrid = v;
        setVar(mExportVarIdx_gTGrid, (v == null) ? 0 : v.getID());
    }

    public Allocation get_gTGrid() {
        return mExportVar_gTGrid;
    }

    private final static int mExportVarIdx_gTestMesh = 3;
    private SimpleMesh mExportVar_gTestMesh;
    public void set_gTestMesh(SimpleMesh v) {
        mExportVar_gTestMesh = v;
        setVar(mExportVarIdx_gTestMesh, (v == null) ? 0 : v.getID());
    }

    public SimpleMesh get_gTestMesh() {
        return mExportVar_gTestMesh;
    }

    private final static int mExportVarIdx_gPFSBackground = 4;
    private ProgramStore mExportVar_gPFSBackground;
    public void set_gPFSBackground(ProgramStore v) {
        mExportVar_gPFSBackground = v;
        setVar(mExportVarIdx_gPFSBackground, (v == null) ? 0 : v.getID());
    }

    public ProgramStore get_gPFSBackground() {
        return mExportVar_gPFSBackground;
    }

    private final static int mExportVarIdx_gRotate = 5;
    private float mExportVar_gRotate;
    public void set_gRotate(float v) {
        mExportVar_gRotate = v;
        setVar(mExportVarIdx_gRotate, v);
    }

    public float get_gRotate() {
        return mExportVar_gRotate;
    }

}


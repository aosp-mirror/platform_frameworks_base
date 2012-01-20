/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.scenegraph;

import java.lang.Math;
import java.util.ArrayList;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramRaster;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RSRuntimeException;
import android.renderscript.RenderScript;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public class RenderState extends SceneGraphBase {
    VertexShader mVertex;
    FragmentShader mFragment;
    ProgramStore mStore;
    ProgramRaster mRaster;

    ScriptField_RenderState_s mField;

    public RenderState(VertexShader pv,
                       FragmentShader pf,
                       ProgramStore ps,
                       ProgramRaster pr) {
        mVertex = pv;
        mFragment = pf;
        mStore = ps;
        mRaster = pr;
    }

    public RenderState(ProgramVertex pv,
                       ProgramFragment pf,
                       ProgramStore ps,
                       ProgramRaster pr) {
        // Just to fix the build for now
    }

    public RenderState(RenderState r) {
        mVertex = r.mVertex;
        mFragment = r.mFragment;
        mStore = r.mStore;
        mRaster = r.mRaster;
    }

    public void setProgramVertex(VertexShader pv) {
        mVertex = pv;
    }

    public void setProgramFragment(FragmentShader pf) {
        mFragment = pf;
    }

    public void setProgramStore(ProgramStore ps) {
        mStore = ps;
    }

    public void setProgramRaster(ProgramRaster pr) {
        mRaster = pr;
    }

    public ScriptField_RenderState_s getRSData(RenderScriptGL rs) {
        if (mField != null) {
            return mField;
        }

        ScriptField_RenderState_s.Item item = new ScriptField_RenderState_s.Item();
        item.pv = mVertex.getRSData(rs).getAllocation();
        item.pf = mFragment.getRSData(rs).getAllocation();
        item.ps = mStore;
        item.pr = mRaster;

        mField = new ScriptField_RenderState_s(rs, 1);
        mField.set(item, 0, true);
        return mField;
    }
}

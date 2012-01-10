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

import android.renderscript.RenderScriptGL;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.util.Log;

/**
 * @hide
 */
public abstract class ShaderParam extends SceneGraphBase {
    static final int FLOAT4_DATA = 0;
    static final int FLOAT4_CAMERA_POS = 1;
    static final int FLOAT4_CAMERA_DIR = 2;
    static final int FLOAT4_LIGHT_COLOR = 3;
    static final int FLOAT4_LIGHT_POS = 4;
    static final int FLOAT4_LIGHT_DIR = 5;

    static final int TRANSFORM_DATA = 100;
    static final int TRANSFORM_VIEW = 101;
    static final int TRANSFORM_PROJ = 102;
    static final int TRANSFORM_VIEW_PROJ = 103;
    static final int TRANSFORM_MODEL = 104;
    static final int TRANSFORM_MODEL_VIEW = 105;
    static final int TRANSFORM_MODEL_VIEW_PROJ = 106;

    static final int TEXTURE = 200;

    static final String cameraPos        = "cameraPos";
    static final String cameraDir        = "cameraDir";

    static final String lightColor       = "lightColor";
    static final String lightPos         = "lightPos";
    static final String lightDir         = "lightDir";

    static final String view             = "view";
    static final String proj             = "proj";
    static final String viewProj         = "viewProj";
    static final String model            = "model";
    static final String modelView        = "modelView";
    static final String modelViewProj    = "modelViewProj";

    ScriptField_ShaderParam_s.Item mRsFieldItem;

    String mParamName;
    int mOffset;

    public ShaderParam(String name) {
        mParamName = name;
    }

    public String getParamName() {
        return mParamName;
    }

    void setOffset(int offset) {
        mOffset = offset;
    }

    abstract void initLocalData(RenderScriptGL rs);

    public ScriptField_ShaderParam_s.Item getRSData(RenderScriptGL rs) {
        if (mRsFieldItem != null) {
            return mRsFieldItem;
        }

        mRsFieldItem = new ScriptField_ShaderParam_s.Item();
        initLocalData(rs);
        return mRsFieldItem;
    }
}






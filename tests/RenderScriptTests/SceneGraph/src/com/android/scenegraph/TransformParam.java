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
public class TransformParam extends ShaderParam {

    Transform mTransform;
    LightBase mLight;

    public TransformParam(String name) {
        super(name);
    }

    public void setTransform(Transform t) {
        mTransform = t;
        if (mField != null && mTransform != null) {
            mData.transform = mTransform.getRSData().getAllocation();
        }
        incTimestamp();
    }

    int getTypeFromName() {
        int paramType = ScriptC_export.const_ShaderParam_TRANSFORM_DATA;
        if (mParamName.equalsIgnoreCase(view)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_VIEW;
        } else if(mParamName.equalsIgnoreCase(proj)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_PROJ;
        } else if(mParamName.equalsIgnoreCase(viewProj)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_VIEW_PROJ;
        } else if(mParamName.equalsIgnoreCase(model)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_MODEL;
        } else if(mParamName.equalsIgnoreCase(modelView)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_MODEL_VIEW;
        } else if(mParamName.equalsIgnoreCase(modelViewProj)) {
            paramType = ScriptC_export.const_ShaderParam_TRANSFORM_MODEL_VIEW_PROJ;
        }
        return paramType;
    }

    void initLocalData() {
        mData.type = getTypeFromName();
        if (mTransform != null) {
            mData.transform = mTransform.getRSData().getAllocation();
        }
        if (mCamera != null) {
            mData.camera = mCamera.getRSData().getAllocation();
        }
        if (mLight != null) {
            mData.light = mLight.getRSData().getAllocation();
        }
    }
}






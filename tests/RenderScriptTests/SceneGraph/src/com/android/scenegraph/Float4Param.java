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

import com.android.scenegraph.Scene;
import com.android.scenegraph.SceneManager;

import android.renderscript.Element;
import android.renderscript.Float4;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public class Float4Param extends ShaderParam {
    private static String TAG = "Float4Param";

    LightBase mLight;

    public Float4Param(String name) {
        super(name);
    }

    public Float4Param(String name, float x) {
        super(name);
        set(x, 0, 0, 0);
    }

    public Float4Param(String name, float x, float y) {
        super(name);
        set(x, y, 0, 0);
    }

    public Float4Param(String name, float x, float y, float z) {
        super(name);
        set(x, y, z, 0);
    }

    public Float4Param(String name, float x, float y, float z, float w) {
        super(name);
        set(x, y, z, w);
    }

    void set(float x, float y, float z, float w) {
        mData.float_value.x = x;
        mData.float_value.y = y;
        mData.float_value.z = z;
        mData.float_value.w = w;
        if (mField != null) {
            mField.set_float_value(0, mData.float_value, true);
        }
        incTimestamp();
    }

    public void setValue(Float4 v) {
        set(v.x, v.y, v.z, v.w);
    }

    public Float4 getValue() {
        return mData.float_value;
    }

    public void setLight(LightBase l) {
        mLight = l;
        if (mField != null) {
            mData.light = mLight.getRSData().getAllocation();
            mField.set_light(0, mData.light, true);
        }
        incTimestamp();
    }

    boolean findLight(String property) {
        String indexStr = mParamName.substring(property.length() + 1);
        if (indexStr == null) {
            Log.e(TAG, "Invalid light index.");
            return false;
        }
        int index = Integer.parseInt(indexStr);
        if (index == -1) {
            return false;
        }
        Scene parentScene = SceneManager.getInstance().getActiveScene();
        ArrayList<LightBase> allLights = parentScene.getLights();
        if (index >= allLights.size()) {
            return false;
        }
        mLight = allLights.get(index);
        if (mLight == null) {
            return false;
        }
        return true;
    }

    int getTypeFromName() {
        int paramType = ScriptC_export.const_ShaderParam_FLOAT4_DATA;
        if (mParamName.equalsIgnoreCase(cameraPos)) {
            paramType = ScriptC_export.const_ShaderParam_FLOAT4_CAMERA_POS;
        } else if(mParamName.equalsIgnoreCase(cameraDir)) {
            paramType = ScriptC_export.const_ShaderParam_FLOAT4_CAMERA_DIR;
        } else if(mParamName.startsWith(lightColor) && findLight(lightColor)) {
            paramType = ScriptC_export.const_ShaderParam_FLOAT4_LIGHT_COLOR;
        } else if(mParamName.startsWith(lightPos) && findLight(lightPos)) {
            paramType = ScriptC_export.const_ShaderParam_FLOAT4_LIGHT_POS;
        } else if(mParamName.startsWith(lightDir) && findLight(lightDir)) {
            paramType = ScriptC_export.const_ShaderParam_FLOAT4_LIGHT_DIR;
        }
        return paramType;
    }

    void initLocalData() {
        mData.type = getTypeFromName();
        if (mCamera != null) {
            mData.camera = mCamera.getRSData().getAllocation();
        }
        if (mLight != null) {
            mData.light = mLight.getRSData().getAllocation();
        }
    }
}






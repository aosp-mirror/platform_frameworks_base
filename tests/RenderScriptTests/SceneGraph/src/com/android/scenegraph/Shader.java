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
import java.util.HashMap;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramFragment.Builder;
import android.util.Log;

/**
 * @hide
 */
public abstract class Shader extends SceneGraphBase {
    protected Type mPerObjConstants;
    protected Type mPerShaderConstants;

    protected HashMap<String, ShaderParam> mSourceParams;
    protected ArrayList<ShaderParam> mParamList;
    protected ArrayList<String> mShaderTextureNames;
    protected ArrayList<Program.TextureType > mShaderTextureTypes;
    protected ArrayList<String> mTextureNames;
    protected ArrayList<Program.TextureType > mTextureTypes;

    protected Allocation mConstantBuffer;
    protected Allocation mConstantBufferParams;

    public Shader() {
        mSourceParams = new HashMap<String, ShaderParam>();
        mParamList = new ArrayList<ShaderParam>();
        mShaderTextureNames = new ArrayList<String>();
        mShaderTextureTypes = new ArrayList<Program.TextureType>();
        mTextureNames = new ArrayList<String>();
        mTextureTypes = new ArrayList<Program.TextureType>();
    }

    public void appendSourceParams(ShaderParam p) {
        mSourceParams.put(p.getParamName(), p);
    }

    public Type getObjectConstants() {
        return mPerObjConstants;
    }

    public Type getShaderConstants() {
        return mPerObjConstants;
    }

    void linkConstants(RenderScriptGL rs, Resources res) {
        if (mPerShaderConstants == null) {
            return;
        }

        Element constElem = mPerShaderConstants.getElement();
        ShaderParam.fillInParams(constElem, mSourceParams, null, mParamList);

        mConstantBuffer = Allocation.createTyped(rs, mPerShaderConstants);

        ScriptField_ShaderParam_s rsParams = null;
        int paramCount = mParamList.size();
        if (paramCount != 0) {
            rsParams = new ScriptField_ShaderParam_s(rs, paramCount);
            for (int i = 0; i < paramCount; i++) {
                rsParams.set(mParamList.get(i).getRSData(rs), i, false);
            }
            rsParams.copyAll();
            mConstantBufferParams = rsParams.getAllocation();
        }
    }
}

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

import com.android.scenegraph.Transform;

import android.renderscript.Element;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public abstract class ShaderParam extends SceneGraphBase {

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

    static void fillInParams(Element constantElem,
                             HashMap<String, ShaderParam> sourceParams,
                             Transform transform,
                             ArrayList<ShaderParam> paramList) {
        int subElemCount = constantElem.getSubElementCount();
        for (int i = 0; i < subElemCount; i ++) {
            String inputName = constantElem.getSubElementName(i);
            int offset = constantElem.getSubElementOffsetBytes(i);

            ShaderParam matchingParam = sourceParams.get(inputName);
            Element subElem = constantElem.getSubElement(i);
            // Make one if it's not there
            if (matchingParam == null) {
                if (subElem.getDataType() == Element.DataType.FLOAT_32) {
                    matchingParam = new Float4Param(inputName);
                } else if (subElem.getDataType() == Element.DataType.MATRIX_4X4) {
                    TransformParam trParam = new TransformParam(inputName);
                    trParam.setTransform(transform);
                    matchingParam = trParam;
                }
            }
            matchingParam.setOffset(offset);
            if (subElem.getDataType() == Element.DataType.FLOAT_32) {
                Float4Param fParam = (Float4Param)matchingParam;
                fParam.setVecSize(subElem.getVectorSize());
            }
            paramList.add(matchingParam);
        }
    }

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
        mRsFieldItem.transformTimestamp = 0;
        if (mParamName != null) {
            mRsFieldItem.paramName = SceneManager.getCachedAlloc(mParamName);
            if (mRsFieldItem.paramName == null) {
                mRsFieldItem.paramName = SceneManager.getStringAsAllocation(rs, mParamName);
                SceneManager.cacheAlloc(mParamName, mRsFieldItem.paramName);
            }
        }
        initLocalData(rs);
        return mRsFieldItem;
    }
}






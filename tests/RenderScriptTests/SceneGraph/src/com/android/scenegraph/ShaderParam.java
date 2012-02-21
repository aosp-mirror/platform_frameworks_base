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

import com.android.scenegraph.SceneManager;
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

    static final long sMaxTimeStamp = 0xffffffffL;

    ScriptField_ShaderParamData_s.Item mData;
    ScriptField_ShaderParamData_s mField;

    String mParamName;
    Camera mCamera;

    static ScriptField_ShaderParam_s fillInParams(Element constantElem,
                                                  HashMap<String, ShaderParam> sourceParams,
                                                  Transform transform) {
        RenderScriptGL rs = SceneManager.getRS();
        ArrayList<ScriptField_ShaderParam_s.Item> paramList;
        paramList = new ArrayList<ScriptField_ShaderParam_s.Item>();

        int subElemCount = constantElem.getSubElementCount();
        for (int i = 0; i < subElemCount; i ++) {
            String inputName = constantElem.getSubElementName(i);
            int offset = constantElem.getSubElementOffsetBytes(i);

            ShaderParam matchingParam = sourceParams.get(inputName);
            Element subElem = constantElem.getSubElement(i);
            // Make one if it's not there
            if (matchingParam == null) {
                if (subElem.getDataType() == Element.DataType.FLOAT_32) {
                    matchingParam = new Float4Param(inputName, 0.5f, 0.5f, 0.5f, 0.5f);
                } else if (subElem.getDataType() == Element.DataType.MATRIX_4X4) {
                    TransformParam trParam = new TransformParam(inputName);
                    trParam.setTransform(transform);
                    matchingParam = trParam;
                }
            }
            ScriptField_ShaderParam_s.Item paramRS = new ScriptField_ShaderParam_s.Item();
            paramRS.bufferOffset = offset;
            paramRS.transformTimestamp = 0;
            paramRS.dataTimestamp = 0;
            paramRS.data = matchingParam.getRSData().getAllocation();
            if (subElem.getDataType() == Element.DataType.FLOAT_32) {
                paramRS.float_vecSize = subElem.getVectorSize();
            }

            paramList.add(paramRS);
        }

        ScriptField_ShaderParam_s rsParams = null;
        int paramCount = paramList.size();
        if (paramCount != 0) {
            rsParams = new ScriptField_ShaderParam_s(rs, paramCount);
            for (int i = 0; i < paramCount; i++) {
                rsParams.set(paramList.get(i), i, false);
            }
            rsParams.copyAll();
        }
        return rsParams;
    }

    public ShaderParam(String name) {
        mParamName = name;
        mData = new ScriptField_ShaderParamData_s.Item();
    }

    public String getParamName() {
        return mParamName;
    }

    public void setCamera(Camera c) {
        mCamera = c;
        if (mField != null) {
            mData.camera = mCamera.getRSData().getAllocation();
            mField.set_camera(0, mData.camera, true);
        }
    }

    protected void incTimestamp() {
        if (mField != null) {
            mData.timestamp ++;
            mData.timestamp %= sMaxTimeStamp;
            mField.set_timestamp(0, mData.timestamp, true);
        }
    }

    abstract void initLocalData();

    public ScriptField_ShaderParamData_s getRSData() {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        mField = new ScriptField_ShaderParamData_s(rs, 1);

        if (mParamName != null) {
            mData.paramName = SceneManager.getCachedAlloc(mParamName);
            if (mData.paramName == null) {
                mData.paramName = SceneManager.getStringAsAllocation(rs, mParamName);
                SceneManager.cacheAlloc(mParamName, mData.paramName);
            }
        }
        initLocalData();
        mData.timestamp = 1;

        mField.set(mData, 0, true);
        return mField;
    }
}






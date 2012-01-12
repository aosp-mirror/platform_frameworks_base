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

import android.renderscript.Float3;
import android.renderscript.Float4;
import android.renderscript.Matrix4f;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public abstract class LightBase extends SceneGraphBase {
    static final int RS_LIGHT_POINT = 0;
    static final int RS_LIGHT_DIRECTIONAL = 1;

    ScriptField_Light_s mField;
    ScriptField_Light_s.Item mFieldData;
    Transform mTransform;
    Float4 mColor;
    float mIntensity;
    public LightBase() {
        mColor = new Float4(0.0f, 0.0f, 0.0f, 0.0f);
        mIntensity = 1.0f;
    }

    public void setTransform(Transform t) {
        mTransform = t;
    }

    public void setColor(float r, float g, float b) {
        mColor.x = r;
        mColor.y = g;
        mColor.z = b;
    }

    public void setColor(Float3 c) {
        mColor.x = c.x;
        mColor.y = c.y;
        mColor.z = c.z;
    }

    public void setIntensity(float i) {
        mIntensity = i;
    }

    abstract void initLocalData();

    protected void updateBaseData(RenderScriptGL rs) {
        if (mField == null) {
            mField = new ScriptField_Light_s(rs, 1);
            mFieldData = new ScriptField_Light_s.Item();
        }

        mFieldData.transformMatrix = mTransform.getRSData(rs).getAllocation();
        mFieldData.name = SceneManager.getStringAsAllocation(rs, getName());
        mFieldData.color = mColor;
        mFieldData.intensity = mIntensity;
    }

    ScriptField_Light_s getRSData(RenderScriptGL rs) {
        updateBaseData(rs);
        initLocalData();

        mField.set(mFieldData, 0, true);

        return mField;
    }
}






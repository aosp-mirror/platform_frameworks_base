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

import com.android.scenegraph.SceneManager;

import android.renderscript.*;
import android.renderscript.Matrix4f;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public class Camera extends SceneGraphBase {

    Transform mTransform;

    ScriptField_Camera_s.Item mData;
    ScriptField_Camera_s mField;

    public Camera() {
        mData = new ScriptField_Camera_s.Item();
        mData.near = 0.1f;
        mData.far = 1000.0f;
        mData.horizontalFOV = 60.0f;
        mData.aspect = 0;
    }

    public void setTransform(Transform t) {
        mTransform = t;
        if (mField != null) {
            mField.set_transformMatrix(0, mTransform.getRSData().getAllocation(), true);
            mField.set_isDirty(0, 1, true);
        }
    }
    public void setFOV(float fov) {
        mData.horizontalFOV = fov;
        if (mField != null) {
            mField.set_horizontalFOV(0, fov, true);
            mField.set_isDirty(0, 1, true);
        }
    }

    public void setNear(float n) {
        mData.near = n;
        if (mField != null) {
            mField.set_near(0, n, true);
            mField.set_isDirty(0, 1, true);
        }
    }

    public void setFar(float f) {
        mData.far = f;
        if (mField != null) {
            mField.set_far(0, f, true);
            mField.set_isDirty(0, 1, true);
        }
    }

    public void setName(String n) {
        super.setName(n);
        if (mField != null) {
            RenderScriptGL rs = SceneManager.getRS();
            mData.name = getNameAlloc(rs);
            mField.set_name(0, mData.name, true);
            mField.set_isDirty(0, 1, true);
        }
    }

    ScriptField_Camera_s getRSData() {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        if (rs == null) {
            return null;
        }

        if (mTransform == null) {
            throw new RuntimeException("Cameras without transforms are invalid");
        }

        mField = new ScriptField_Camera_s(rs, 1);

        mData.transformMatrix = mTransform.getRSData().getAllocation();
        mData.transformTimestamp = 1;
        mData.timestamp = 1;
        mData.isDirty = 1;
        mData.name = getNameAlloc(rs);
        mField.set(mData, 0, true);

        return mField;
    }
}






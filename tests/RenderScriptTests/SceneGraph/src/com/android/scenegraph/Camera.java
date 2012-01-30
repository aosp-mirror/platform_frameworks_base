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
    float mFOV;
    float mNear;
    float mFar;

    ScriptField_Camera_s mField;

    public Camera() {
        mFOV = 60.0f;
        mNear = 0.1f;
        mFar = 100.0f;
    }

    public void setTransform(Transform t) {
        mTransform = t;
        updateRSData();
    }
    public void setFOV(float fov) {
        mFOV = fov;
        updateRSData();
    }

    public void setNear(float n) {
        mNear = n;
        updateRSData();
    }

    public void setFar(float f) {
        mFar = f;
        updateRSData();
    }

    public void setName(String n) {
        super.setName(n);
        updateRSData();
    }

    ScriptField_Camera_s getRSData() {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        if (rs == null) {
            return null;
        }

        mField = new ScriptField_Camera_s(rs, 1);
        updateRSData();
        return mField;
    }

    void updateRSData() {
        if (mField == null) {
            return;
        }
        RenderScriptGL rs = SceneManager.getRS();
        if (rs == null) {
            return;
        }

        ScriptField_Camera_s.Item cam = new ScriptField_Camera_s.Item();
        cam.horizontalFOV = mFOV;
        cam.near = mNear;
        cam.far = mFar;
        cam.transformMatrix = mTransform.getRSData().getAllocation();
        cam.name = getNameAlloc(rs);
        mField.set(cam, 0, true);
    }
}






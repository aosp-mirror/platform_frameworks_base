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

import android.graphics.Camera;
import android.renderscript.Float4;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.util.Log;

/**
 * @hide
 */
public class Float4Param extends ShaderParam {

    public static final int VALUE = 0;
    public static final int CAMERA_POSITION = 1;
    public static final int CAMERA_DIRECTION = 2;
    public static final int LIGHT_POSITION = 3;
    public static final int LIGHT_COLOR = 4;
    public static final int LIGHT_DIRECTION = 5;
    Float4 mValue;
    Camera mCamera;
    LightBase mLight;

    public Float4Param(String name) {
        super(name);
    }

    public void setValue(Float4 v) {
        mValue = v;
    }

    public Float4 getValue() {
        return mValue;
    }

    public void setCamera(Camera c) {
        mCamera = c;
    }

    public void setLight(LightBase l) {
        mLight = l;
    }
}






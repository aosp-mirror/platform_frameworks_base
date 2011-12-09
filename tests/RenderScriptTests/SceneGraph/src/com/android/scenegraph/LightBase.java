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
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.util.Log;

/**
 * @hide
 */
public class LightBase extends SceneGraphBase {
    Transform mTransform;
    Float3 mColor;
    float mIntensity;
    public LightBase() {
        mColor = new Float3(0.0f, 0.0f, 0.0f);
        mIntensity = 1.0f;
    }

    public void setTransform(Transform t) {
        mTransform = t;
    }

    public void setColor(Float3 c) {
        mColor = c;
    }

    public void setIntensity(float i) {
        mIntensity = i;
    }
}






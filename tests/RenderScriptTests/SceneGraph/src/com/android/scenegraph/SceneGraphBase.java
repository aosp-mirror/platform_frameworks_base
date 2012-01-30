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

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RSRuntimeException;
import android.renderscript.RenderScript;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public abstract class SceneGraphBase {
    String mName;
    Allocation mNameAlloc;
    public void setName(String n) {
        mName = n;
        mNameAlloc = null;
    }

    public String getName() {
        return mName;
    }

    Allocation getNameAlloc(RenderScriptGL rs) {
        if (mNameAlloc == null)  {
            mNameAlloc = SceneManager.getStringAsAllocation(rs, getName());
        }
        return mNameAlloc;
    }
}






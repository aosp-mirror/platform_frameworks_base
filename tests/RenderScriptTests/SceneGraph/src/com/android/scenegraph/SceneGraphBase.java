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

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RenderScript;
import android.renderscript.RSRuntimeException;

import android.util.Log;

/**
 * @hide
 */
public abstract class SceneGraphBase {
    String mName;
    public void setName(String n) {
        mName = n;
    }

    public String getName() {
        return mName;
    }

    Allocation getStringAsAllocation(RenderScript rs, String str) {
        if (str == null) {
            return null;
        }
        if (str.length() == 0) {
            return null;
        }
        byte[] allocArray = null;
        byte[] nullChar = new byte[1];
        nullChar[0] = 0;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs),
                                                      allocArray.length + 1,
                                                      Allocation.USAGE_SCRIPT);
            alloc.copy1DRangeFrom(0, allocArray.length, allocArray);
            alloc.copy1DRangeFrom(allocArray.length, 1, nullChar);
            return alloc;
        }
        catch (Exception e) {
            throw new RSRuntimeException("Could not convert string to utf-8.");
        }
    }
}






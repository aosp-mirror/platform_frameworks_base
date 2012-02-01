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

import android.renderscript.*;
import android.renderscript.Matrix4f;
import android.util.Log;

/**
 * @hide
 */
public abstract class Transform extends SceneGraphBase {
    Transform mParent;
    ArrayList<Transform> mChildren;

    ScriptField_SgTransform mField;
    ScriptField_SgTransform.Item mTransformData;

    public Transform() {
        mChildren = new ArrayList<Transform>();
        mParent = null;
    }

    public void appendChild(Transform t) {
        mChildren.add(t);
        t.mParent = this;
        updateRSChildData(true);
    }

    abstract void initLocalData();

    void updateRSChildData(boolean copyData) {
        if (mField == null) {
            return;
        }
        RenderScriptGL rs = SceneManager.getRS();
        if (mChildren.size() != 0) {
            Allocation childRSData = Allocation.createSized(rs, Element.ALLOCATION(rs),
                                                            mChildren.size());
            mTransformData.children = childRSData;

            Allocation[] childrenAllocs = new Allocation[mChildren.size()];
            for (int i = 0; i < mChildren.size(); i ++) {
                Transform child = mChildren.get(i);
                childrenAllocs[i] = child.getRSData().getAllocation();
            }
            childRSData.copyFrom(childrenAllocs);
        }
        if (copyData) {
            mField.set(mTransformData, 0, true);
        }
    }

    ScriptField_SgTransform getRSData() {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        if (rs == null) {
            return null;
        }
        mField = new ScriptField_SgTransform(rs, 1);

        mTransformData = new ScriptField_SgTransform.Item();
        mTransformData.name = getNameAlloc(rs);
        mTransformData.isDirty = 1;
        mTransformData.timestamp = 1;

        initLocalData();
        updateRSChildData(false);

        mField.set(mTransformData, 0, true);
        return mField;
    }
}






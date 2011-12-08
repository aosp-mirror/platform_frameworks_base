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

    RenderScriptGL mRS;
    Transform mParent;
    ArrayList<Transform> mChildren;

    static final int RS_ID_NONE = 0;
    static final int RS_ID_TRANSLATE = 1;
    static final int RS_ID_ROTATE = 2;
    static final int RS_ID_SCALE = 3;

    ScriptField_SgTransform mField;
    ScriptField_SgTransform.Item mTransformData;

    public Transform() {
        mChildren = new ArrayList<Transform>();
        mParent = null;
    }

    public void appendChild(Transform t) {
        mChildren.add(t);
        t.mParent = this;
    }

    abstract void initLocalData();
    public abstract void updateRSData();

    public ScriptField_SgTransform getRSData(RenderScriptGL rs) {
        if (mField != null) {
            return mField;
        }

        mRS = rs;
        initLocalData();

        if (mChildren.size() != 0) {
            Allocation childRSData = Allocation.createSized(rs,
                                                            Element.ALLOCATION(rs),
                                                            mChildren.size());
            mTransformData.children = childRSData;

            Allocation[] childrenAllocs = new Allocation[mChildren.size()];
            for (int i = 0; i < mChildren.size(); i ++) {
                Transform child = mChildren.get(i);
                childrenAllocs[i] = child.getRSData(rs).getAllocation();
            }
            childRSData.copyFrom(childrenAllocs);
        }

        mField = new ScriptField_SgTransform(rs, 1);
        mField.set(mTransformData, 0, true);

        return mField;
    }
}






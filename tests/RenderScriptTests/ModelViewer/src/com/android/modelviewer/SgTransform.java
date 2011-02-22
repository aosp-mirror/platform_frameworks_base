/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.modelviewer;

import java.io.Writer;
import java.util.Map;
import java.util.Vector;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.Element.Builder;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;

enum TransformType {

    NONE(0),
    TRANSLATE(1),
    ROTATE(2),
    SCALE(3);

    int mID;
    TransformType(int id) {
        mID = id;
    }
}

public class SgTransform {


    ScriptField_SgTransform mTransformField;
    ScriptField_SgTransform mChildField;
    public ScriptField_SgTransform.Item mTransformData;

    RenderScript mRS;

    Vector mChildren;
    SgTransform mParent;
    int mIndexInParentGroup;

    public void setParent(SgTransform parent, int parentIndex) {
        mParent = parent;
        mIndexInParentGroup = parentIndex;
    }

    public void addChild(SgTransform child) {
        mChildren.add(child);
        child.setParent(this, mChildren.size() - 1);
    }

    public void setTransform(int index, Float4 value, TransformType type) {
        mTransformData.transforms[index] = value;
        mTransformData.transformTypes[index] = type.mID;
    }

    void initData() {
        int numElements = mTransformData.transforms.length;
        mTransformData.transformTypes = new int[numElements];
        for (int i = 0; i < numElements; i ++) {
            mTransformData.transforms[i] = new Float4(0, 0, 0, 0);
            mTransformData.transformTypes[i] = TransformType.NONE.mID;
        }

        mTransformData.isDirty = 1;
        mTransformData.children = null;
    }

    public SgTransform(RenderScript rs) {
        mRS = rs;
        mTransformData = new ScriptField_SgTransform.Item();
        mChildren = new Vector();
        initData();
    }

    public ScriptField_SgTransform.Item getData() {
        if (mChildren.size() != 0) {
            mChildField = new ScriptField_SgTransform(mRS, mChildren.size());
            mTransformData.children = mChildField.getAllocation();

            for (int i = 0; i < mChildren.size(); i ++) {
                SgTransform child = (SgTransform)mChildren.get(i);
                mChildField.set(child.getData(), i, false);
            }
            mChildField.copyAll();
        }

        return mTransformData;
    }

    public ScriptField_SgTransform getField() {
        mTransformField = new ScriptField_SgTransform(mRS, 1);
        mTransformField.set(getData(), 0, true);
        return mTransformField;
    }
}




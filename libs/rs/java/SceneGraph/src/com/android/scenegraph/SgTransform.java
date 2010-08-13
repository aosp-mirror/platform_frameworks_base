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

package com.android.scenegraph;

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

    Float4[] mTransforms;
    TransformType[] mTransformTypes;

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
        mTransforms[index] = value;
        mTransformTypes[index] = type;
    }

    void initData() {
        int numTransforms = 16;
        mTransforms = new Float4[numTransforms];
        mTransformTypes = new TransformType[numTransforms];
        for(int i = 0; i < numTransforms; i ++) {
            mTransforms[i] = new Float4(0, 0, 0, 0);
            mTransformTypes[i] = TransformType.NONE;
        }
    }

    void setData() {

        mTransformData.globalMat_Row0 = new Float4(1, 0, 0, 0);
        mTransformData.globalMat_Row1 = new Float4(0, 1, 0, 0);
        mTransformData.globalMat_Row2 = new Float4(0, 0, 1, 0);
        mTransformData.globalMat_Row3 = new Float4(0, 0, 0, 1);

        mTransformData.localMat_Row0 = new Float4(1, 0, 0, 0);
        mTransformData.localMat_Row1 = new Float4(0, 1, 0, 0);
        mTransformData.localMat_Row2 = new Float4(0, 0, 1, 0);
        mTransformData.localMat_Row3 = new Float4(0, 0, 0, 1);

        mTransformData.transforms0 = mTransforms[0];
        mTransformData.transforms1 = mTransforms[1];
        mTransformData.transforms2 = mTransforms[2];
        mTransformData.transforms3 = mTransforms[3];
        mTransformData.transforms4 = mTransforms[4];
        mTransformData.transforms5 = mTransforms[5];
        mTransformData.transforms6 = mTransforms[6];
        mTransformData.transforms7 = mTransforms[7];
        mTransformData.transforms8 = mTransforms[8];
        mTransformData.transforms9 = mTransforms[9];
        mTransformData.transforms10 = mTransforms[10];
        mTransformData.transforms11 = mTransforms[11];
        mTransformData.transforms12 = mTransforms[12];
        mTransformData.transforms13 = mTransforms[13];
        mTransformData.transforms14 = mTransforms[14];
        mTransformData.transforms15 = mTransforms[15];

        mTransformData.transformType0 = mTransformTypes[0].mID;
        mTransformData.transformType1 = mTransformTypes[1].mID;
        mTransformData.transformType2 = mTransformTypes[2].mID;
        mTransformData.transformType3 = mTransformTypes[3].mID;
        mTransformData.transformType4 = mTransformTypes[4].mID;
        mTransformData.transformType5 = mTransformTypes[5].mID;
        mTransformData.transformType6 = mTransformTypes[6].mID;
        mTransformData.transformType7 = mTransformTypes[7].mID;
        mTransformData.transformType8 = mTransformTypes[8].mID;
        mTransformData.transformType9 = mTransformTypes[9].mID;
        mTransformData.transformType10 = mTransformTypes[10].mID;
        mTransformData.transformType11 = mTransformTypes[11].mID;
        mTransformData.transformType12 = mTransformTypes[12].mID;
        mTransformData.transformType13 = mTransformTypes[13].mID;
        mTransformData.transformType14 = mTransformTypes[14].mID;
        mTransformData.transformType15 = mTransformTypes[15].mID;

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
        setData();
        if(mChildren.size() != 0) {
            mChildField = new ScriptField_SgTransform(mRS, mChildren.size());
            mTransformData.children = mChildField.getAllocation();

            for(int i = 0; i < mChildren.size(); i ++) {
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




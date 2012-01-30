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
import android.renderscript.Float3;
import android.renderscript.Matrix4f;
import android.util.Log;

/**
 * @hide
 */
public class CompoundTransform extends Transform {

    public static abstract class Component {
        String mName;
        Allocation mNameAlloc;
        int mRsId;
        Float4 mValue;
        CompoundTransform mParent;

        Allocation getNameAlloc(RenderScriptGL rs) {
            if (mNameAlloc == null)  {
                mNameAlloc = SceneManager.getStringAsAllocation(rs, getName());
            }
            return mNameAlloc;
        }

        public String getName() {
            return mName;
        }
    }

    public static class TranslateComponent extends Component {
        public TranslateComponent(String name, Float3 translate) {
            mRsId = RS_ID_TRANSLATE;
            mName = name;
            mValue = new Float4(translate.x, translate.y, translate.z, 0);
        }
        public Float3 getValue() {
            return new Float3(mValue.x, mValue.y, mValue.z);
        }
        public void setValue(Float3 val) {
            mValue.x = val.x;
            mValue.y = val.y;
            mValue.z = val.z;
            mParent.updateRSComponents(true);
        }
    }

    public static class RotateComponent extends Component {
        public RotateComponent(String name, Float3 axis, float angle) {
            mRsId = RS_ID_ROTATE;
            mName = name;
            mValue = new Float4(axis.x, axis.y, axis.z, angle);
        }
        public Float3 getAxis() {
            return new Float3(mValue.x, mValue.y, mValue.z);
        }
        public float getAngle() {
            return mValue.w;
        }
        public void setAxis(Float3 val) {
            mValue.x = val.x;
            mValue.y = val.y;
            mValue.z = val.z;
            mParent.updateRSComponents(true);
        }
        public void setAngle(float val) {
            mValue.w = val;
            mParent.updateRSComponents(true);
        }
    }

    public static class ScaleComponent extends Component {
        public ScaleComponent(String name, Float3 scale) {
            mRsId = RS_ID_SCALE;
            mName = name;
            mValue = new Float4(scale.x, scale.y, scale.z, 0);
        }
        public Float3 getValue() {
            return new Float3(mValue.x, mValue.y, mValue.z);
        }
        public void setValue(Float3 val) {
            mValue.x = val.x;
            mValue.y = val.y;
            mValue.z = val.z;
            mParent.updateRSComponents(true);
        }
    }

    public ArrayList<Component> mTransformComponents;

    Matrix4f mLocalMatrix;
    Matrix4f mGlobalMatrix;

    public CompoundTransform() {
        mTransformComponents = new ArrayList<Component>();
    }

    public void addComponent(Component c) {
        if (c.mParent != null) {
            throw new IllegalArgumentException("Transform components may not be shared");
        }
        c.mParent = this;
        mTransformComponents.add(c);
        updateRSComponents(true);
    }

    public void setComponent(int index, Component c) {
        if (c.mParent != null) {
            throw new IllegalArgumentException("Transform components may not be shared");
        }
        c.mParent = this;
        mTransformComponents.set(index, c);
        updateRSComponents(true);
    }

    // TODO: Will need to optimize this function a bit, we copy more data than we need to
    void updateRSComponents(boolean copy) {
        if (mField == null) {
            return;
        }
        RenderScriptGL rs = SceneManager.getRS();
        int numElements = mTransformComponents.size();
        for (int i = 0; i < numElements; i ++) {
            Component ith = mTransformComponents.get(i);
            mTransformData.transforms[i] = ith.mValue;
            mTransformData.transformTypes[i] = ith.mRsId;
            mTransformData.transformNames[i] = ith.getNameAlloc(rs);
        }
        // "null" terminate the array
        mTransformData.transformTypes[numElements] = RS_ID_NONE;
        mTransformData.isDirty = 1;
        if (copy) {
            mField.set(mTransformData, 0, true);
        }
    }

    void initLocalData() {
        updateRSComponents(false);
    }
}






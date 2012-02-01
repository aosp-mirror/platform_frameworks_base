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
import android.renderscript.Float3;
import android.renderscript.Matrix4f;
import android.util.Log;

/**
 * @hide
 */
public class CompoundTransform extends Transform {

    public static abstract class Component {
        String mName;
        CompoundTransform mParent;
        int mParentIndex;
        protected ScriptField_TransformComponent_s.Item mData;

        Component(String name) {
            mData = new ScriptField_TransformComponent_s.Item();
            mName = name;
        }

        void setNameAlloc() {
            RenderScriptGL rs = SceneManager.getRS();
            if (mData.name != null)  {
                return;
            }
            mData.name = SceneManager.getCachedAlloc(getName());
            if (mData.name == null) {
                mData.name = SceneManager.getStringAsAllocation(rs, getName());
                SceneManager.cacheAlloc(getName(), mData.name);
            }
        }

        abstract ScriptField_TransformComponent_s.Item getRSData();

        protected void update() {
            if (mParent != null) {
                mParent.updateRSComponent(this);
            }
        }

        public String getName() {
            return mName;
        }
    }

    public static class TranslateComponent extends Component {
        public TranslateComponent(String name, Float3 translate) {
            super(name);
            setValue(translate);
        }
        public Float3 getValue() {
            return new Float3(mData.value.x, mData.value.y, mData.value.z);
        }
        public void setValue(Float3 val) {
            mData.value.x = val.x;
            mData.value.y = val.y;
            mData.value.z = val.z;
            update();
        }
        ScriptField_TransformComponent_s.Item getRSData() {
            setNameAlloc();
            mData.type = SceneManager.getConst().get_transform_TRANSLATE();
            return mData;
        }
    }

    public static class RotateComponent extends Component {
        public RotateComponent(String name, Float3 axis, float angle) {
            super(name);
            setAxis(axis);
            setAngle(angle);
        }
        public Float3 getAxis() {
            return new Float3(mData.value.x, mData.value.y, mData.value.z);
        }
        public float getAngle() {
            return mData.value.w;
        }
        public void setAxis(Float3 val) {
            mData.value.x = val.x;
            mData.value.y = val.y;
            mData.value.z = val.z;
            update();
        }
        public void setAngle(float val) {
            mData.value.w = val;
            update();
        }
        ScriptField_TransformComponent_s.Item getRSData() {
            setNameAlloc();
            mData.type = SceneManager.getConst().get_transform_ROTATE();
            return mData;
        }
    }

    public static class ScaleComponent extends Component {
        public ScaleComponent(String name, Float3 scale) {
            super(name);
            setValue(scale);
        }
        public Float3 getValue() {
            return new Float3(mData.value.x, mData.value.y, mData.value.z);
        }
        public void setValue(Float3 val) {
            mData.value.x = val.x;
            mData.value.y = val.y;
            mData.value.z = val.z;
            update();
        }
        ScriptField_TransformComponent_s.Item getRSData() {
            setNameAlloc();
            mData.type = SceneManager.getConst().get_transform_SCALE();
            return mData;
        }
    }

    ScriptField_TransformComponent_s mComponentField;
    public ArrayList<Component> mTransformComponents;

    public CompoundTransform() {
        mTransformComponents = new ArrayList<Component>();
    }

    public void addComponent(Component c) {
        if (c.mParent != null) {
            throw new IllegalArgumentException("Transform components may not be shared");
        }
        c.mParent = this;
        c.mParentIndex = mTransformComponents.size();
        mTransformComponents.add(c);
        updateRSComponentAllocation();
    }

    public void setComponent(int index, Component c) {
        if (c.mParent != null) {
            throw new IllegalArgumentException("Transform components may not be shared");
        }
        if (index >= mTransformComponents.size()) {
            throw new IllegalArgumentException("Invalid component index");
        }
        c.mParent = this;
        c.mParentIndex = index;
        mTransformComponents.set(index, c);
        updateRSComponent(c);
    }

    void updateRSComponent(Component c) {
        if (mField == null || mComponentField == null) {
            return;
        }
        mComponentField.set(c.getRSData(), c.mParentIndex, true);
        mField.set_isDirty(0, 1, true);
    }

    void updateRSComponentAllocation() {
        if (mField == null) {
            return;
        }
        initLocalData();

        mField.set_components(0, mTransformData.components, false);
        mField.set_isDirty(0, 1, true);
    }

    void initLocalData() {
        RenderScriptGL rs = SceneManager.getRS();
        int numComponenets = mTransformComponents.size();
        if (numComponenets > 0) {
            mComponentField = new ScriptField_TransformComponent_s(rs, numComponenets);
            for (int i = 0; i < numComponenets; i ++) {
                Component ith = mTransformComponents.get(i);
                mComponentField.set(ith.getRSData(), i, false);
            }
            mComponentField.copyAll();

            mTransformData.components = mComponentField.getAllocation();
        }
    }
}






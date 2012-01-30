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

import android.util.Log;

import android.renderscript.*;
import android.content.res.Resources;

/**
 * @hide
 */
public class RenderPass extends SceneGraphBase {

    Allocation mColorTarget;
    Float4 mClearColor;
    boolean mShouldClearColor;

    Allocation mDepthTarget;
    float mClearDepth;
    boolean mShouldClearDepth;

    ArrayList<RenderableBase> mObjectsToDraw;

    Camera mCamera;

    ScriptField_RenderPass_s.Item mRsField;

    public RenderPass() {
        mObjectsToDraw = new ArrayList<RenderableBase>();
        mClearColor = new Float4(0.0f, 0.0f, 0.0f, 0.0f);
        mShouldClearColor = true;
        mClearDepth = 1.0f;
        mShouldClearDepth = true;
    }

    public void appendRenderable(Renderable d) {
        mObjectsToDraw.add(d);
    }

    public void setCamera(Camera c) {
        mCamera = c;
    }

    public void setColorTarget(Allocation colorTarget) {
        mColorTarget = colorTarget;
    }
    public void setClearColor(Float4 clearColor) {
        mClearColor = clearColor;
    }
    public void setShouldClearColor(boolean shouldClearColor) {
        mShouldClearColor = shouldClearColor;
    }

    public void setDepthTarget(Allocation depthTarget) {
        mDepthTarget = depthTarget;
    }
    public void setClearDepth(float clearDepth) {
        mClearDepth = clearDepth;
    }
    public void setShouldClearDepth(boolean shouldClearDepth) {
        mShouldClearDepth = shouldClearDepth;
    }

    public ArrayList<RenderableBase> getRenderables() {
        return mObjectsToDraw;
    }

    ScriptField_RenderPass_s.Item getRsField(RenderScriptGL rs, Resources res) {
        if (mRsField != null) {
            return mRsField;
        }

        mRsField = new ScriptField_RenderPass_s.Item();
        mRsField.color_target = mColorTarget;
        mRsField.depth_target = mDepthTarget;
        mRsField.camera = mCamera != null ? mCamera.getRSData().getAllocation() : null;

        if (mObjectsToDraw.size() != 0) {
            Allocation drawableData = Allocation.createSized(rs,
                                                              Element.ALLOCATION(rs),
                                                              mObjectsToDraw.size());
            Allocation[] drawableAllocs = new Allocation[mObjectsToDraw.size()];
            for (int i = 0; i < mObjectsToDraw.size(); i ++) {
                Renderable dI = (Renderable)mObjectsToDraw.get(i);
                drawableAllocs[i] = dI.getRsField(rs, res).getAllocation();
            }
            drawableData.copyFrom(drawableAllocs);
            mRsField.objects = drawableData;
        }

        mRsField.clear_color = mClearColor;
        mRsField.clear_depth = mClearDepth;
        mRsField.should_clear_color = mShouldClearColor;
        mRsField.should_clear_depth = mShouldClearDepth;
        return mRsField;
    }
}






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

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;


public class ModelViewerRS {

    private final int STATE_LAST_FOCUS = 1;

    int mWidth;
    int mHeight;
    int mRotation;

    public ModelViewerRS() {
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        mRotation = 0;
        initRS();
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private Sampler mSampler;
    private ProgramStore mPSBackground;
    private ProgramFragment mPFBackground;
    private ProgramVertex mPVBackground;
    private ProgramVertex.MatrixAllocation mPVA;

    private Allocation mGridImage;
    private Allocation mAllocPV;

    private SimpleMesh mMesh;

    private Font mItalic;
    private Allocation mTextAlloc;

    private ScriptC_Modelviewer mScript;

    int mLastX;
    int mLastY;

    public void touchEvent(int x, int y) {
        int dx = mLastX - x;
        if(Math.abs(dx) > 50 || Math.abs(dx) < 3) {
            dx = 0;
        }

        mRotation -= dx;
        if(mRotation > 360) {
            mRotation -= 360;
        }
        if(mRotation < 0) {
            mRotation += 360;
        }

        mScript.set_gRotate(-(float)mRotation);

        mLastX = x;
        mLastY = y;
    }

    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS, null, null);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnable(false);
        b.setDepthMask(true);
        mPSBackground = b.create();

        mScript.set_gPFSBackground(mPSBackground);
    }

    private void initPF() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMin(Sampler.Value.LINEAR);
        bs.setMag(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.CLAMP);
        bs.setWrapT(Sampler.Value.WRAP);
        mSampler = bs.create();

        ProgramFragment.Builder b = new ProgramFragment.Builder(mRS);
        b.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                     ProgramFragment.Builder.Format.RGBA, 0);
        mPFBackground = b.create();
        mPFBackground.bindSampler(mSampler, 0);

        mScript.set_gPFBackground(mPFBackground);
    }

    private void initPV() {
        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        mPVBackground = pvb.create();

        mPVA = new ProgramVertex.MatrixAllocation(mRS);
        mPVBackground.bindAllocation(mPVA);
        mPVA.setupProjectionNormalized(mWidth, mHeight);

        mScript.set_gPVBackground(mPVBackground);
    }

    private void loadImage() {
        mGridImage = Allocation.createFromBitmapResourceBoxed(mRS, mRes, R.drawable.robot, Element.RGB_565(mRS), true);
        mGridImage.uploadToTexture(0);

        mScript.set_gTGrid(mGridImage);
    }

    private void initTextAllocation() {
        String allocString = "Displaying file: R.raw.robot";
        mTextAlloc = Allocation.createFromString(mRS, allocString);
        mScript.set_gTextAlloc(mTextAlloc);
    }

    private void initRS() {

        mScript = new ScriptC_Modelviewer(mRS, mRes, R.raw.modelviewer_bc, true);

        initPFS();
        initPF();
        initPV();

        loadImage();

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.robot);
        FileA3D.IndexEntry entry = model.getIndexEntry(0);
        if(entry == null || entry.getClassID() != FileA3D.ClassID.SIMPLE_MESH) {
            Log.e("rs", "could not load model");
        }
        else {
            mMesh = (SimpleMesh)entry.getObject();
            mScript.set_gTestMesh(mMesh);
        }

        mItalic = Font.create(mRS, mRes, "DroidSerif-Italic.ttf", 10);
        mScript.set_gItalic(mItalic);

        initTextAllocation();

        mRS.contextBindRootScript(mScript);
    }
}




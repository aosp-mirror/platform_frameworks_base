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

package com.android.fbotest;

import java.io.Writer;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.Element.DataType;
import android.renderscript.Element.DataKind;
import android.renderscript.ProgramStore.DepthFunc;
import android.renderscript.Type.Builder;
import android.util.Log;


public class FBOSyncRS {

    public FBOSyncRS() {
    }

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void surfaceChanged() {
        mRS.getWidth();
        mRS.getHeight();
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private Sampler mSampler;
    private ProgramStore mPSBackground;
    private ProgramFragment mPFBackground;
    private ProgramVertex mPVBackground;
    private ProgramVertexFixedFunction.Constants mPVA;

    private Allocation mGridImage;
    private Allocation mOffscreen;
    private Allocation mOffscreenDepth;
    private Allocation mAllocPV;
    private Allocation mReadBackTest;

    private Font mItalic;
    private Allocation mTextAlloc;

    private ScriptField_MeshInfo mMeshes;
    private ScriptC_fbosync mScript;


    public void onActionDown(float x, float y) {
        mScript.invoke_onActionDown(x, y);
    }

    public void onActionScale(float scale) {
        mScript.invoke_onActionScale(scale);
    }

    public void onActionMove(float x, float y) {
        mScript.invoke_onActionMove(x, y);
    }

    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnabled(false);
        b.setDepthMaskEnabled(true);
        mPSBackground = b.create();

        mScript.set_gPFSBackground(mPSBackground);
    }

    private void initPF() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMinification(Sampler.Value.LINEAR);
        bs.setMagnification(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.CLAMP);
        bs.setWrapT(Sampler.Value.CLAMP);
        mSampler = bs.create();

        ProgramFragmentFixedFunction.Builder b = new ProgramFragmentFixedFunction.Builder(mRS);
        b.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                     ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mPFBackground = b.create();
        mPFBackground.bindSampler(mSampler, 0);

        mScript.set_gPFBackground(mPFBackground);
    }

    private void initPV() {
        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mPVBackground = pvb.create();

        mPVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)mPVBackground).bindConstants(mPVA);

        mScript.set_gPVBackground(mPVBackground);
    }

    private void loadImage() {
        mGridImage = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.robot,
                                                         Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE);
        mScript.set_gTGrid(mGridImage);
    }

    private void initTextAllocation(String fileName) {
        String allocString = "Displaying file: " + fileName;
        mTextAlloc = Allocation.createFromString(mRS, allocString, Allocation.USAGE_SCRIPT);
        mScript.set_gTextAlloc(mTextAlloc);
    }

    private void initMeshes(FileA3D model) {
        int numEntries = model.getIndexEntryCount();
        int numMeshes = 0;
        for (int i = 0; i < numEntries; i ++) {
            FileA3D.IndexEntry entry = model.getIndexEntry(i);
            if (entry != null && entry.getEntryType() == FileA3D.EntryType.MESH) {
                numMeshes ++;
            }
        }

        if (numMeshes > 0) {
            mMeshes = new ScriptField_MeshInfo(mRS, numMeshes);

            for (int i = 0; i < numEntries; i ++) {
                FileA3D.IndexEntry entry = model.getIndexEntry(i);
                if (entry != null && entry.getEntryType() == FileA3D.EntryType.MESH) {
                    Mesh mesh = entry.getMesh();
                    mMeshes.set_mMesh(i, mesh, false);
                    mMeshes.set_mNumIndexSets(i, mesh.getPrimitiveCount(), false);
                }
            }
            mMeshes.copyAll();
        } else {
            throw new RSRuntimeException("No valid meshes in file");
        }

        mScript.bind_gMeshes(mMeshes);
        mScript.invoke_updateMeshInfo();
    }

    public void loadA3DFile(String path) {
        FileA3D model = FileA3D.createFromFile(mRS, path);
        initMeshes(model);
        initTextAllocation(path);
    }

    private void initRS() {

        mScript = new ScriptC_fbosync(mRS, mRes, R.raw.fbosync);

        initPFS();
        initPF();
        initPV();

        loadImage();

        Type.Builder b = new Type.Builder(mRS, Element.RGBA_8888(mRS));
        b.setX(512).setY(512);
        mOffscreen = Allocation.createTyped(mRS,
                                            b.create(),
                                            Allocation.USAGE_SCRIPT |
                                            Allocation.USAGE_GRAPHICS_TEXTURE |
                                            Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gOffscreen(mOffscreen);

        mReadBackTest = Allocation.createTyped(mRS,
                                               b.create(),
                                               Allocation.USAGE_SCRIPT |
                                               Allocation.USAGE_GRAPHICS_TEXTURE);
        mScript.set_gReadBackTest(mReadBackTest);

        b = new Type.Builder(mRS,
                             Element.createPixel(mRS, DataType.UNSIGNED_16,
                             DataKind.PIXEL_DEPTH));
        b.setX(512).setY(512);
        mOffscreenDepth = Allocation.createTyped(mRS,
                                                 b.create(),
                                                 Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gOffscreenDepth(mOffscreenDepth);

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.robot);
        initMeshes(model);

        mItalic = Font.create(mRS, mRes, "serif", Font.Style.ITALIC, 8);
        mScript.set_gItalic(mItalic);

        initTextAllocation("R.raw.robot");

        mRS.bindRootScript(mScript);
    }
}




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

package com.android.shaderstest;

import android.content.res.Resources;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Element.DataKind;
import android.renderscript.Element.DataType;
import android.renderscript.FileA3D;
import android.renderscript.Mesh;
import android.renderscript.Program;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramFragmentFixedFunction;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramStore.DepthFunc;
import android.renderscript.ProgramVertex;
import android.renderscript.ProgramVertexFixedFunction;
import android.renderscript.RSRuntimeException;
import android.renderscript.RenderScriptGL;
import android.renderscript.Sampler;
import android.renderscript.Type.Builder;

@SuppressWarnings({"FieldCanBeLocal"})
public class ShadersTestRS {
    public ShadersTestRS() {
    }

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void surfaceChanged() {
        initBuffers(mRS.getWidth(), mRS.getHeight());
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private Sampler mLinearClamp;
    private Sampler mNearestClamp;
    private ProgramStore mPSBackground;
    private ProgramFragment mPFBackground;
    private ProgramVertex mPVBackground;
    private ProgramVertexFixedFunction.Constants mPVA;

    private ProgramFragment mPFVignette;
    private ScriptField_VignetteConstants_s mFSVignetteConst;

    private Allocation mMeshTexture;
    private Allocation mScreen;
    private Allocation mScreenDepth;

    private ScriptField_MeshInfo mMeshes;
    private ScriptC_shaderstest mScript;


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

        b.setDepthFunc(DepthFunc.LESS);
        b.setDitherEnabled(false);
        b.setDepthMaskEnabled(true);
        mPSBackground = b.create();

        mScript.set_gPFSBackground(mPSBackground);
    }

    private void initPF() {
        mLinearClamp = Sampler.CLAMP_LINEAR(mRS);
        mScript.set_gLinear(mLinearClamp);

        mNearestClamp = Sampler.CLAMP_NEAREST(mRS);
        mScript.set_gNearest(mNearestClamp);

        ProgramFragmentFixedFunction.Builder b = new ProgramFragmentFixedFunction.Builder(mRS);
        b.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                     ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mPFBackground = b.create();
        mPFBackground.bindSampler(mLinearClamp, 0);
        mScript.set_gPFBackground(mPFBackground);

        mFSVignetteConst = new ScriptField_VignetteConstants_s(mRS, 1);
        mScript.bind_gFSVignetteConstants(mFSVignetteConst);

        ProgramFragment.Builder fs;

        fs = new ProgramFragment.Builder(mRS);
        fs.setShader(mRes, R.raw.vignette_fs);
        fs.addConstant(mFSVignetteConst.getAllocation().getType());
        fs.addTexture(Program.TextureType.TEXTURE_2D);
        mPFVignette = fs.create();
        mPFVignette.bindConstants(mFSVignetteConst.getAllocation(), 0);
        mScript.set_gPFVignette(mPFVignette);
    }

    private void initPV() {
        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mPVBackground = pvb.create();

        mPVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction) mPVBackground).bindConstants(mPVA);

        mScript.set_gPVBackground(mPVBackground);
    }

    private void loadImage() {
        mMeshTexture = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.robot,
                Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
        mScript.set_gTMesh(mMeshTexture);
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

    private void initRS() {
        mScript = new ScriptC_shaderstest(mRS, mRes, R.raw.shaderstest);

        initPFS();
        initPF();
        initPV();

        loadImage();

        initBuffers(1, 1);

        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.robot);
        initMeshes(model);

        mRS.bindRootScript(mScript);
    }

    private void initBuffers(int width, int height) {
        Builder b;
        b = new Builder(mRS, Element.RGBA_8888(mRS));
        b.setX(width).setY(height);
        mScreen = Allocation.createTyped(mRS, b.create(),
                Allocation.USAGE_GRAPHICS_TEXTURE | Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gScreen(mScreen);

        b = new Builder(mRS, Element.createPixel(mRS, DataType.UNSIGNED_16, DataKind.PIXEL_DEPTH));
        b.setX(width).setY(height);
        mScreenDepth = Allocation.createTyped(mRS, b.create(),
                Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mScript.set_gScreenDepth(mScreenDepth);
    }
}

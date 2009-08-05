/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.fall.rs;

import android.content.res.Resources;
import android.renderscript.RenderScript;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Element;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.CLAMP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.Element.*;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;

import java.util.TimeZone;

class FallRS {
    private static final int MESH_RESOLUTION = 32;

    private static final int RSID_STATE = 0;
    private static final int RSID_STATE_FRAMECOUNT = 0;
    private static final int RSID_STATE_WIDTH = 1;
    private static final int RSID_STATE_HEIGHT = 2;

    private static final int RSID_TEXTURES = 1;
    private static final int TEXTURES_COUNT = 0;

    private Resources mResources;
    private RenderScript mRS;
    private final BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();

    private final int mWidth;
    private final int mHeight;

    private ScriptC mScript;
    private Sampler mSampler;
    private ProgramFragment mPfBackground;
    private ProgramStore mPfsBackground;
    private ProgramVertex mPvBackground;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;

    private Allocation mTexturesIDs;
    private Allocation[] mTextures;
    private int[] mTextureBufferIDs;

    private Allocation mState;
    private RenderScript.TriangleMesh mMesh;

    public FallRS(int width, int height) {
        mWidth = width;
        mHeight = height;
        mBitmapOptions.inScaled = false;
        mBitmapOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    public void init(RenderScript rs, Resources res) {
        mRS = rs;
        mResources = res;
        initRS();
    }

    public void destroy() {
        mScript.destroy();
        mSampler.destroy();
        mPfBackground.destroy();
        mPfsBackground.destroy();
        mPvBackground.destroy();
        mPvOrthoAlloc.mAlloc.destroy();
        mTexturesIDs.destroy();
        for (Allocation a : mTextures) {
            a.destroy();
        }
        mState.destroy();
        mTextureBufferIDs = null;
        mMesh.destroy();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    private void initRS() {
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createScriptStructures();
        createMesh();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mResources, R.raw.fall);
        sb.setRoot(true);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.setTimeZone(TimeZone.getDefault().getID());

        loadSkyTextures();
        mScript.bindAllocation(mState, RSID_STATE);
        mScript.bindAllocation(mTexturesIDs, RSID_TEXTURES);

        mRS.contextBindRootScript(mScript);
    }

    private void createMesh() {
        final RenderScript rs = mRS;
        rs.triangleMeshBegin(Element.XYZ_F32, Element.INDEX_16);

        int wResolution;
        int hResolution;

        final int width = mWidth;
        final int height = mHeight;

        if (width < height) {
            wResolution = MESH_RESOLUTION;
            hResolution = (int) (MESH_RESOLUTION * height / (float) width);
        } else {
            wResolution = (int) (MESH_RESOLUTION * width / (float) height);
            hResolution = MESH_RESOLUTION;
        }

        final float quadWidth = width / (float) wResolution;
        final float quadHeight = height / (float) hResolution;

        for (int y = 0; y <= hResolution; y++) {
            final float yOffset = y * quadHeight;
            for (int x = 0; x <= wResolution; x++) {
                rs.triangleMeshAddVertex_XYZ(x * quadWidth, yOffset, 0.0f);
            }
        }

        for (int y = 0; y < hResolution; y++) {
            for (int x = 0; x < wResolution; x++) {
                final int index = y * (wResolution + 1) + x;
                final int iWR1 = index + wResolution + 1;
                rs.triangleMeshAddTriangle(index, index + 1, iWR1);
                rs.triangleMeshAddTriangle(index + 1, iWR1, iWR1 + 1);
            }
        }

        mMesh = rs.triangleMeshCreate();
        mMesh.setName("mesh");
    }

    private void createScriptStructures() {
        final int[] data = new int[3];
        mState = Allocation.createSized(mRS, USER_I32, data.length);
        data[RSID_STATE_FRAMECOUNT] = 0;
        data[RSID_STATE_WIDTH] = mWidth;
        data[RSID_STATE_HEIGHT] = mHeight;
        mState.data(data);
    }

    private void loadSkyTextures() {
        mTextureBufferIDs = new int[TEXTURES_COUNT];
        mTextures = new Allocation[TEXTURES_COUNT];
        mTexturesIDs = Allocation.createSized(mRS, USER_FLOAT, TEXTURES_COUNT);

        final Allocation[] textures = mTextures;
        // TOOD: Load textures

        final int[] bufferIds = mTextureBufferIDs;
        final int count = textures.length;

        for (int i = 0; i < count; i++) {
            final Allocation texture = textures[i];
            texture.uploadToTexture(0);
            bufferIds[i] = texture.getID();
        }

        mTexturesIDs.data(bufferIds);
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565, false);
        allocation.setName(name);
        return allocation;
    }

    private Allocation loadTextureARGB(int id, String name) {
        // Forces ARGB 32 bits, because pngcrush sometimes optimize our PNGs to
        // indexed pictures, which are not well supported
        final Bitmap b = BitmapFactory.decodeResource(mResources, id, mBitmapOptions);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888, false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMin(LINEAR);
        bs.setMag(LINEAR);
        bs.setWrapS(CLAMP);
        bs.setWrapT(CLAMP);
        mSampler = bs.create();

        ProgramFragment.Builder b;
        b = new ProgramFragment.Builder(mRS, null, null);
        b.setTexEnable(true, 0);
        b.setTexEnvMode(REPLACE, 0);
        mPfBackground = b.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder b;
        b = new ProgramStore.Builder(mRS, null, null);

        b.setDepthFunc(ALWAYS);
        b.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        b.setDitherEnable(true);
        b.setDepthMask(false);
        mPfsBackground = b.create();
        mPfsBackground.setName("PFSBackground");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        pvb.setTextureMatrixEnable(true);
        mPvBackground = pvb.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
    }
}

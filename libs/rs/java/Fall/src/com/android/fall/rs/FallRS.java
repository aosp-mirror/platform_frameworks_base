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
import android.renderscript.Light;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.CLAMP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.Element.*;

import java.util.TimeZone;

class FallRS {
    private static final int MESH_RESOLUTION = 48;

    private static final int RSID_STATE = 0;
    private static final int RSID_STATE_FRAMECOUNT = 0;
    private static final int RSID_STATE_WIDTH = 1;
    private static final int RSID_STATE_HEIGHT = 2;
    private static final int RSID_STATE_MESH_WIDTH = 3;
    private static final int RSID_STATE_MESH_HEIGHT = 4;
    private static final int RSID_STATE_RIPPLE_MAP_SIZE = 5;
    private static final int RSID_STATE_RIPPLE_INDEX = 6;
    private static final int RSID_STATE_DROP_X = 7;
    private static final int RSID_STATE_DROP_Y = 8;
    private static final int RSID_STATE_RUNNING = 9;
    
    private static final int RSID_TEXTURES = 1;
    private static final int TEXTURES_COUNT = 1;
    private static final int RSID_TEXTURE_RIVERBED = 0;

    private static final int RSID_RIPPLE_MAP = 2;

    private static final int RSID_REFRACTION_MAP = 3;

    private boolean mIsRunning = true;    
    
    private Resources mResources;
    private RenderScript mRS;

    private final int mWidth;
    private final int mHeight;

    private ScriptC mScript;
    private Sampler mSampler;
    private ProgramFragment mPfBackground;
    private ProgramFragment mPfLighting;
    private ProgramStore mPfsBackground;
    private ProgramVertex mPvBackground;
    private ProgramVertex mPvLines;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    private Light mLight;

    private Allocation mTexturesIDs;
    private Allocation[] mTextures;
    private int[] mTextureBufferIDs;

    private Allocation mState;
    private RenderScript.TriangleMesh mMesh;
    private int mMeshWidth;
    private int mMeshHeight;

    private Allocation mRippleMap;
    private Allocation mRefractionMap;

    public FallRS(int width, int height) {
        mWidth = width;
        mHeight = height;
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
        mLight.destroy();
        mRippleMap.destroy();
        mRefractionMap.destroy();
        mPvLines.destroy();
        mPfLighting.destroy();
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
        createMesh();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mResources, R.raw.fall);
        sb.setRoot(true);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.setTimeZone(TimeZone.getDefault().getID());

        mScript.bindAllocation(mState, RSID_STATE);
        mScript.bindAllocation(mTexturesIDs, RSID_TEXTURES);
        mScript.bindAllocation(mRippleMap, RSID_RIPPLE_MAP);
        mScript.bindAllocation(mRefractionMap, RSID_REFRACTION_MAP);

        mRS.contextBindRootScript(mScript);
    }

    private void createMesh() {
        final RenderScript rs = mRS;
        rs.triangleMeshBegin(Element.NORM_ST_XYZ_F32, Element.INDEX_16);

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

        final float glHeight = 2.0f * height / (float) width;
        final float quadWidth = 2.0f / (float) wResolution;
        final float quadHeight = glHeight / (float) hResolution;

        wResolution += 2;
        hResolution += 2;        
        
        for (int y = 0; y <= hResolution; y++) {
            final float yOffset = y * quadHeight - glHeight / 2.0f - quadHeight;
            final float t = 1.0f - y / (float) hResolution;
            for (int x = 0; x <= wResolution; x++) {
                rs.triangleMeshAddVertex_XYZ_ST_NORM(
                        -1.0f + x * quadWidth - quadWidth, yOffset, 0.0f,
                        x / (float) wResolution, t,
                        0.0f, 0.0f, -1.0f);
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

        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;
    }

    private void createScriptStructures() {
        final int rippleMapSize = (mMeshWidth + 2) * (mMeshHeight + 2);

        final int[] data = new int[10];
        mState = Allocation.createSized(mRS, USER_I32, data.length);
        data[RSID_STATE_FRAMECOUNT] = 0;
        data[RSID_STATE_WIDTH] = mWidth;
        data[RSID_STATE_HEIGHT] = mHeight;
        data[RSID_STATE_MESH_WIDTH] = mMeshWidth;
        data[RSID_STATE_MESH_HEIGHT] = mMeshHeight;
        data[RSID_STATE_RIPPLE_MAP_SIZE] = rippleMapSize;
        data[RSID_STATE_RIPPLE_INDEX] = 0;
        data[RSID_STATE_DROP_X] = mMeshWidth / 2;
        data[RSID_STATE_DROP_Y] = mMeshHeight / 2;
        data[RSID_STATE_RUNNING] = 1;
        mState.data(data);

        final int[] rippleMap = new int[rippleMapSize * 2];
        mRippleMap = Allocation.createSized(mRS, USER_I32, rippleMap.length);

        final int[] refractionMap = new int[513];
        float ir = 1.0f / 1.333f;
        for (int i = 0; i < refractionMap.length; i++) {
            float d = (float) Math.tan(Math.asin(Math.sin(Math.atan(i * (1.0f / 256.0f))) * ir));
            refractionMap[i] = (int) Math.floor(d * (1 << 16) + 0.5f);
        }
        mRefractionMap = Allocation.createSized(mRS, USER_I32, refractionMap.length);
        mRefractionMap.data(refractionMap);
    }

    private void loadTextures() {
        mTextureBufferIDs = new int[TEXTURES_COUNT];
        mTextures = new Allocation[TEXTURES_COUNT];
        mTexturesIDs = Allocation.createSized(mRS, USER_FLOAT, TEXTURES_COUNT);

        final Allocation[] textures = mTextures;
        textures[RSID_TEXTURE_RIVERBED] = loadTexture(R.drawable.riverbed, "TRiverbed");

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

    private void createProgramFragment() {
        Sampler.Builder sampleBuilder = new Sampler.Builder(mRS);
        sampleBuilder.setMin(LINEAR);
        sampleBuilder.setMag(LINEAR);
        sampleBuilder.setWrapS(CLAMP);
        sampleBuilder.setWrapT(CLAMP);
        mSampler = sampleBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);
        
        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(false, 0);
        mPfLighting = builder.create();
        mPfLighting.setName("PFLighting");
        mPfLighting.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(true);
        builder.setDepthMask(true);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);

        mLight = new Light.Builder(mRS).create();
        mLight.setPosition(0.0f, 2.0f, -8.0f);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        builder.setTextureMatrixEnable(true);
        builder.addLight(mLight);
        mPvBackground = builder.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
        
        builder = new ProgramVertex.Builder(mRS, null, null);
        mPvLines = builder.create();
        mPvLines.bindAllocation(mPvOrthoAlloc);
        mPvLines.setName("PVLines");
    }

    void addDrop(float x, float y) {
        mState.subData1D(RSID_STATE_DROP_X, 2, new int[] {
                (int) ((x / mWidth) * mMeshWidth), (int) ((y / mHeight) * mMeshHeight)
        });
    }
    
    void togglePause() {
        mIsRunning = !mIsRunning;
        mState.subData1D(RSID_STATE_RUNNING, 1, new int[] { mIsRunning ? 1 : 0 });
    }
}

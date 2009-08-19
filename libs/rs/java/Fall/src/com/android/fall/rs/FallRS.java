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
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import static android.util.MathUtils.*;

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
    private static final int RSID_STATE_LEAVES_COUNT = 10;

    private static final int TEXTURES_COUNT = 3;
    private static final int LEAVES_TEXTURES_COUNT = 4;
    private static final int RSID_TEXTURE_RIVERBED = 0;
    private static final int RSID_TEXTURE_LEAVES = 1;
    private static final int RSID_TEXTURE_SKY = 2;

    private static final int RSID_RIPPLE_MAP = 1;
    
    private static final int RSID_REFRACTION_MAP = 2;

    private static final int RSID_LEAVES = 3;
    private static final int LEAVES_COUNT = 14;
    private static final int LEAF_STRUCT_FIELDS_COUNT = 11;
    private static final int LEAF_STRUCT_X = 0;
    private static final int LEAF_STRUCT_Y = 1;
    private static final int LEAF_STRUCT_SCALE = 2;
    private static final int LEAF_STRUCT_ANGLE = 3;
    private static final int LEAF_STRUCT_SPIN = 4;
    private static final int LEAF_STRUCT_U1 = 5;
    private static final int LEAF_STRUCT_U2 = 6;
    private static final int LEAF_STRUCT_ALTITUDE = 7;
    private static final int LEAF_STRUCT_RIPPLED = 8;
    private static final int LEAF_STRUCT_DELTAX = 9;
    private static final int LEAF_STRUCT_DELTAY = 10;

    private static final int RSID_GL_STATE = 4;    
    private static final int RSID_STATE_GL_WIDTH = 0;
    private static final int RSID_STATE_GL_HEIGHT = 1;
    
    private boolean mIsRunning = true;    
    
    private Resources mResources;
    private RenderScript mRS;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    private final int mWidth;
    private final int mHeight;

    private ScriptC mScript;
    private Sampler mSampler;
    private ProgramFragment mPfBackground;
    private ProgramFragment mPfLighting;
    private ProgramFragment mPfSky;
    private ProgramStore mPfsBackground;
    private ProgramStore mPfsLeaf;
    private ProgramVertex mPvBackground;
    private ProgramVertex mPvLines;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    private Light mLight;

    private Allocation[] mTextures;

    private Allocation mState;
    private RenderScript.TriangleMesh mMesh;
    private int mMeshWidth;
    private int mMeshHeight;

    private Allocation mRippleMap;
    private Allocation mRefractionMap;
    private Allocation mLeaves;

    private Allocation mGlState;
    private float mGlHeight;

    public FallRS(int width, int height) {
        mWidth = width;
        mHeight = height;
        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
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
        for (Allocation a : mTextures) {
            a.destroy();
        }
        mState.destroy();
        mMesh.destroy();
        mLight.destroy();
        mRippleMap.destroy();
        mRefractionMap.destroy();
        mPvLines.destroy();
        mPfLighting.destroy();
        mLeaves.destroy();
        mPfsLeaf.destroy();
        mPfSky.destroy();
        mGlState.destroy();
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
        mScript.bindAllocation(mRippleMap, RSID_RIPPLE_MAP);
        mScript.bindAllocation(mRefractionMap, RSID_REFRACTION_MAP);
        mScript.bindAllocation(mLeaves, RSID_LEAVES);
        mScript.bindAllocation(mGlState, RSID_GL_STATE);

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

        mGlHeight = 2.0f * height / (float) width;
        final float glHeight = mGlHeight;

        float quadWidth = 2.0f / (float) wResolution;
        float quadHeight = glHeight / (float) hResolution;
        
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

        createState(rippleMapSize);
        createGlState();
        createRippleMap(rippleMapSize);
        createRefractionMap();
        createLeaves();
    }

    private void createLeaves() {
        final float[] leaves = new float[LEAVES_COUNT * LEAF_STRUCT_FIELDS_COUNT];
        mLeaves = Allocation.createSized(mRS, USER_FLOAT, leaves.length);
        for (int i = 0; i < leaves.length; i += LEAF_STRUCT_FIELDS_COUNT) {
            createLeaf(leaves, i);
        }
        mLeaves.data(leaves);
    }

    private void createRefractionMap() {
        final int[] refractionMap = new int[513];
        float ir = 1.0f / 1.333f;
        for (int i = 0; i < refractionMap.length; i++) {
            float d = (float) Math.tan(Math.asin(Math.sin(Math.atan(i * (1.0f / 256.0f))) * ir));
            refractionMap[i] = (int) Math.floor(d * (1 << 16) + 0.5f);
        }
        mRefractionMap = Allocation.createSized(mRS, USER_I32, refractionMap.length);
        mRefractionMap.data(refractionMap);
    }

    private void createRippleMap(int rippleMapSize) {
        final int[] rippleMap = new int[rippleMapSize * 2];
        mRippleMap = Allocation.createSized(mRS, USER_I32, rippleMap.length);
        mRippleMap.data(rippleMap);
    }

    private void createGlState() {
        final float[] meshState = new float[2];
        mGlState = Allocation.createSized(mRS, USER_FLOAT, meshState.length);
        meshState[RSID_STATE_GL_WIDTH] = 2.0f;
        meshState[RSID_STATE_GL_HEIGHT] = mGlHeight;
        mGlState.data(meshState);
    }

    private void createState(int rippleMapSize) {
        final int[] data = new int[11];
        mState = Allocation.createSized(mRS, USER_I32, data.length);
        data[RSID_STATE_FRAMECOUNT] = 0;
        data[RSID_STATE_WIDTH] = mWidth;
        data[RSID_STATE_HEIGHT] = mHeight;
        data[RSID_STATE_MESH_WIDTH] = mMeshWidth;
        data[RSID_STATE_MESH_HEIGHT] = mMeshHeight;
        data[RSID_STATE_RIPPLE_MAP_SIZE] = rippleMapSize;
        data[RSID_STATE_RIPPLE_INDEX] = 0;
        data[RSID_STATE_DROP_X] = -1;
        data[RSID_STATE_DROP_Y] = -1;
        data[RSID_STATE_RUNNING] = 1;
        data[RSID_STATE_LEAVES_COUNT] = LEAVES_COUNT;
        mState.data(data);
    }

    private void createLeaf(float[] leaves, int index) {
        int sprite = random(LEAVES_TEXTURES_COUNT);
        //noinspection PointlessArithmeticExpression
        leaves[index + LEAF_STRUCT_X] = random(-1.0f, 1.0f);
        leaves[index + LEAF_STRUCT_Y] = random(-mGlHeight / 2.0f, mGlHeight / 2.0f);
        leaves[index + LEAF_STRUCT_SCALE] = random(0.4f, 0.5f);
        leaves[index + LEAF_STRUCT_ANGLE] = random(0.0f, 360.0f);
        leaves[index + LEAF_STRUCT_SPIN] = degrees(random(-0.02f, 0.02f)) / 4.0f;
        leaves[index + LEAF_STRUCT_U1] = sprite / (float) LEAVES_TEXTURES_COUNT;
        leaves[index + LEAF_STRUCT_U2] = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
        leaves[index + LEAF_STRUCT_ALTITUDE] = -1.0f;
        leaves[index + LEAF_STRUCT_RIPPLED] = 1.0f;
        leaves[index + LEAF_STRUCT_DELTAX] = random(-0.02f, 0.02f) / 60.0f;
        leaves[index + LEAF_STRUCT_DELTAY] = -0.08f * random(0.9f, 1.1f) / 60.0f;
    }

    private void loadTextures() {
        mTextures = new Allocation[TEXTURES_COUNT];

        final Allocation[] textures = mTextures;
        textures[RSID_TEXTURE_RIVERBED] = loadTexture(R.drawable.riverbed, "TRiverbed");
        textures[RSID_TEXTURE_LEAVES] = loadTextureARGB(R.drawable.leaves, "TLeaves");
        textures[RSID_TEXTURE_SKY] = loadTextureARGB(R.drawable.sky, "TSky");

        final int count = textures.length;
        for (int i = 0; i < count; i++) {
            final Allocation texture = textures[i];
            texture.uploadToTexture(0);
        }
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565, false);
        allocation.setName(name);
        return allocation;
    }

    private Allocation loadTextureARGB(int id, String name) {
        Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888, false);
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
        
        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
        mPfSky = builder.create();
        mPfSky.setName("PFSky");
        mPfSky.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");

        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsLeaf = builder.create();
        mPfsLeaf.setName("PFSLeaf");
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

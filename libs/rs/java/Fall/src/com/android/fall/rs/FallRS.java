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
import android.renderscript.Type;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.WRAP;
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

    class Leaf {
        float x;
        float y;
        float scale;
        float angle;
        float spin;
        float u1;
        float u2;
        float altitude;
        float rippled;
        float deltaX;
        float deltaY;
    }

    private static final int RSID_DROP = 4;

    private Resources mResources;
    private RenderScript mRS;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    private final int mWidth;
    private final int mHeight;

    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfLighting;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfSky;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsLeaf;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvLight;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvSky;

    private Allocation mState;
    private Allocation mDropState;
    private DropState mDrop;
    private Type mStateType;
    private Type mDropType;
    private int mMeshWidth;

    private int mMeshHeight;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.TriangleMesh mMesh;

    private Allocation mRippleMap;
    private Allocation mRefractionMap;

    private Allocation mLeaves;
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

    private void initRS() {
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createMesh();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setType(mDropType, "Drop", RSID_DROP);
        sb.setScript(mResources, R.raw.fall);
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mRippleMap, RSID_RIPPLE_MAP);
        script.bindAllocation(mRefractionMap, RSID_REFRACTION_MAP);
        script.bindAllocation(mLeaves, RSID_LEAVES);
        script.bindAllocation(mDropState, RSID_DROP);

        mRS.contextBindRootScript(script);
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
            final boolean shift = (y & 0x1) == 0;
            final float yOffset = y * quadHeight - glHeight / 2.0f - quadHeight;
            final float t = 1.0f - y / (float) hResolution;
            for (int x = 0; x <= wResolution; x++) {
                if (shift) {
                    rs.triangleMeshAddVertex_XYZ_ST_NORM(
                            -1.0f + x * quadWidth - quadWidth, yOffset, 0.0f,
                            x / (float) wResolution, t,
                            0.0f, 0.0f, -1.0f);
                } else {
                    rs.triangleMeshAddVertex_XYZ_ST_NORM(
                            -1.0f + x * quadWidth - quadWidth * 0.5f, yOffset, 0.0f,
                            x / (float) wResolution, t,
                            0.0f, 0.0f, -1.0f);
                }
            }
        }

        for (int y = 0; y < hResolution; y++) {
            final boolean shift = (y & 0x1) == 0;
            final int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                final int index = yOffset + x;
                final int iWR1 = index + wResolution + 1;
                if (shift) {
                    rs.triangleMeshAddTriangle(index, index + 1, iWR1);
                    rs.triangleMeshAddTriangle(index + 1, iWR1 + 1, iWR1);
                } else {
                    rs.triangleMeshAddTriangle(index, iWR1 + 1, iWR1);
                    rs.triangleMeshAddTriangle(index, index + 1, iWR1 + 1);
                }
            }
        }

        mMesh = rs.triangleMeshCreate();
        mMesh.setName("WaterMesh");

        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;
    }

    private void createScriptStructures() {
        final int rippleMapSize = (mMeshWidth + 2) * (mMeshHeight + 2);

        createState(rippleMapSize);
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

    static class WorldState {
        public int frameCount;
        public int width;
        public int height;
        public int meshWidth;
        public int meshHeight;
        public int rippleMapSize;
        public int rippleIndex;
        public int leavesCount;
        public float glWidth;
        public float glHeight;
        public float skySpeedX;
        public float skySpeedY;
    }

    static class DropState {
        public int dropX;
        public int dropY;
    }

    private void createState(int rippleMapSize) {
        WorldState worldState = new WorldState();
        worldState.width = mWidth;
        worldState.height = mHeight;
        worldState.meshWidth = mMeshWidth;
        worldState.meshHeight = mMeshHeight;
        worldState.rippleMapSize = rippleMapSize;
        worldState.rippleIndex = 0;
        worldState.leavesCount = LEAVES_COUNT;
        worldState.glWidth = 2.0f;
        worldState.glHeight = mGlHeight;
        worldState.skySpeedX = random(-0.001f, 0.001f);
        worldState.skySpeedY = random(0.00008f, 0.0002f);

        mStateType = Type.createFromClass(mRS, WorldState.class, 1, "WorldState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(worldState);

        mDrop = new DropState();
        mDrop.dropX = -1;
        mDrop.dropY = -1;

        mDropType = Type.createFromClass(mRS, DropState.class, 1, "DropState");
        mDropState = Allocation.createTyped(mRS, mDropType);
        mDropState.data(mDrop);
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
        final Allocation[] textures = new Allocation[TEXTURES_COUNT];
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
        sampleBuilder.setWrapS(WRAP);
        sampleBuilder.setWrapT(WRAP);
        Sampler sampler = sampleBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(sampler, 0);

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(false, 0);
        mPfLighting = builder.create();
        mPfLighting.setName("PFLighting");
        mPfLighting.bindSampler(sampler, 0);

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
        mPfSky = builder.create();
        mPfSky.setName("PFSky");
        mPfSky.bindSampler(sampler, 0);
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
        ProgramVertex.MatrixAllocation pvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        pvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);

        Light light = new Light.Builder(mRS).create();
        light.setPosition(0.0f, 2.0f, -8.0f);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        builder.addLight(light);
        mPvLight = builder.create();
        mPvLight.bindAllocation(pvOrthoAlloc);
        mPvLight.setName("PVLight");

        builder = new ProgramVertex.Builder(mRS, null, null);
        builder.setTextureMatrixEnable(true);
        mPvSky = builder.create();
        mPvSky.bindAllocation(pvOrthoAlloc);
        mPvSky.setName("PVSky");
    }

    void addDrop(float x, float y) {
        mDrop.dropX = (int) ((x / mWidth) * mMeshWidth);
        mDrop.dropY = (int) ((y / mHeight) * mMeshHeight);
        mDropState.data(mDrop);
    }
}

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

package com.android.galaxy.rs;

import android.content.res.Resources;
import android.renderscript.RenderScript;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.CLAMP;
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

class GalaxyRS {
    private static final int GALAXY_RADIUS = 300;
    private static final int PARTICLES_COUNT = 12000;
    private static final float GALAXY_HEIGHT = 0.1f;

    private static final int RSID_STATE = 0;
    private static final int RSID_STATE_FRAMECOUNT = 0;
    private static final int RSID_STATE_WIDTH = 1;
    private static final int RSID_STATE_HEIGHT = 2;
    private static final int RSID_STATE_PARTICLES_COUNT = 3;
    private static final int RSID_STATE_GALAXY_RADIUS = 4;

    private static final int TEXTURES_COUNT = 4;
    private static final int PARTICLES_TEXTURES_COUNT = 2;
    private static final int RSID_TEXTURE_SPACE = 0;
    private static final int RSID_TEXTURE_LIGHT1 = 1;
    private static final int RSID_TEXTURE_LIGHT2 = 2;
    private static final int RSID_TEXTURE_FLARES = 3;

    private static final int RSID_PARTICLES = 1;
    private static final int PARTICLE_STRUCT_FIELDS_COUNT = 7;
    private static final int PARTICLE_STRUCT_ANGLE = 0;
    private static final int PARTICLE_STRUCT_DISTANCE = 1;
    private static final int PARTICLE_STRUCT_SPEED = 2;
    private static final int PARTICLE_STRUCT_Z = 3;
    private static final int PARTICLE_STRUCT_RADIUS = 4;
    private static final int PARTICLE_STRUCT_U1 = 5;
    private static final int PARTICLE_STRUCT_U2 = 6;

    private static final int RSID_PARTICLES_BUFFER = 2;

    private Resources mResources;
    private RenderScript mRS;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    private final int mWidth;
    private final int mHeight;

    private ScriptC mScript;
    private Sampler mSampler;
    private Sampler mLightSampler;
    private ProgramFragment mPfBackground;
    private ProgramFragment mPfLighting;
    private ProgramStore mPfsBackground;
    private ProgramStore mPfsLights;
    private ProgramVertex mPvBackground;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;

    private Allocation[] mTextures;

    private Allocation mState;
    private Allocation mParticles;
    private Allocation mParticlesBuffer;
    private SimpleMesh mParticlesMesh;

    public GalaxyRS(int width, int height) {
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
        mLightSampler.destroy();
        mPfBackground.destroy();
        mPfsBackground.destroy();
        mPvBackground.destroy();
        mPvOrthoAlloc.mAlloc.destroy();
        for (Allocation a : mTextures) {
            a.destroy();
        }
        mState.destroy();
        mPfLighting.destroy();
        mParticles.destroy();
        mPfsLights.destroy();
        mParticlesMesh.destroy();
        mParticlesBuffer.destroy();
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
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setScript(mResources, R.raw.galaxy);
        sb.setRoot(true);
        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.setTimeZone(TimeZone.getDefault().getID());

        mScript.bindAllocation(mState, RSID_STATE);
        mScript.bindAllocation(mParticles, RSID_PARTICLES);
        mScript.bindAllocation(mParticlesBuffer, RSID_PARTICLES_BUFFER);

        mRS.contextBindRootScript(mScript);
    }

    private void createScriptStructures() {
        createState();
        createParticles();
        createParticlesMesh();
    }

    private void createParticlesMesh() {
        final Element.Builder elementBuilder = new Element.Builder(mRS);
        elementBuilder.add(Element.DataType.UNSIGNED, Element.DataKind.RED, true, 8);
        elementBuilder.add(Element.DataType.UNSIGNED, Element.DataKind.GREEN, true, 8);
        elementBuilder.add(Element.DataType.UNSIGNED, Element.DataKind.BLUE, true, 8);
        elementBuilder.add(Element.DataType.UNSIGNED, Element.DataKind.ALPHA, true, 8);
        elementBuilder.add(Element.DataType.FLOAT, Element.DataKind.X, false, 32);
        elementBuilder.add(Element.DataType.FLOAT, Element.DataKind.Y, false, 32);
        elementBuilder.add(Element.DataType.FLOAT, Element.DataKind.Z, false, 32);
        elementBuilder.add(Element.DataType.FLOAT, Element.DataKind.S, false, 32);
        elementBuilder.add(Element.DataType.FLOAT, Element.DataKind.T, false, 32);
        final Element vertexElement = elementBuilder.create();

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(vertexElement, PARTICLES_COUNT * 3);
        meshBuilder.setPrimitive(Primitive.TRIANGLE);
        mParticlesMesh = meshBuilder.create();
        mParticlesMesh.setName("MParticles");

        mParticlesBuffer = mParticlesMesh.createVertexAllocation(vertexSlot);
        mParticlesBuffer.setName("BParticles");
        mParticlesMesh.bindVertexAllocation(mParticlesBuffer, 0);
    }

    private void createParticles() {
        final float[] particles = new float[PARTICLES_COUNT * PARTICLE_STRUCT_FIELDS_COUNT];
        mParticles = Allocation.createSized(mRS, USER_FLOAT, particles.length);
        for (int i = 0; i < particles.length; i += PARTICLE_STRUCT_FIELDS_COUNT) {
            createParticle(particles, i);
        }
        mParticles.data(particles);
    }

    private void createState() {
        final int[] data = new int[5];

        mState = Allocation.createSized(mRS, USER_I32, data.length);
        data[RSID_STATE_FRAMECOUNT] = 0;
        data[RSID_STATE_WIDTH] = mWidth;
        data[RSID_STATE_HEIGHT] = mHeight;
        data[RSID_STATE_PARTICLES_COUNT] = PARTICLES_COUNT;
        data[RSID_STATE_GALAXY_RADIUS] = GALAXY_RADIUS;
        mState.data(data);
    }

    @SuppressWarnings({"PointlessArithmeticExpression"})
    private void createParticle(float[] particles, int index) {
        int sprite = random(PARTICLES_TEXTURES_COUNT);
        float d = abs(randomGauss()) * GALAXY_RADIUS / 2.0f;

        particles[index + PARTICLE_STRUCT_ANGLE] = random(0.0f, (float) (Math.PI * 2.0));
        particles[index + PARTICLE_STRUCT_DISTANCE] = d;
        particles[index + PARTICLE_STRUCT_SPEED] = random(0.0015f, 0.0025f);
        particles[index + PARTICLE_STRUCT_Z] = randomGauss() * GALAXY_HEIGHT *
                (GALAXY_RADIUS - d) / (float) GALAXY_RADIUS;
        particles[index + PARTICLE_STRUCT_RADIUS] = random(3.0f, 7.5f);
        particles[index + PARTICLE_STRUCT_U1] = sprite / (float) PARTICLES_TEXTURES_COUNT;
        particles[index + PARTICLE_STRUCT_U2] = (sprite + 1) / (float) PARTICLES_TEXTURES_COUNT;
    }

    private static float randomGauss() {
        float x1;
        float x2;
        float w;

        do {
            x1 = 2.0f * random(0.0f, 1.0f) - 1.0f;
            x2 = 2.0f * random(0.0f, 1.0f) - 1.0f;
            w = x1 * x1 + x2 * x2;
        } while (w >= 1.0f);

        w = (float) Math.sqrt(-2.0 * log(w) / w);
        return x1 * w;
    }
    
    private void loadTextures() {
        mTextures = new Allocation[TEXTURES_COUNT];

        final Allocation[] textures = mTextures;
        textures[RSID_TEXTURE_SPACE] = loadTexture(R.drawable.space, "TSpace");
        textures[RSID_TEXTURE_LIGHT1] = loadTextureARGB(R.drawable.light1, "TLight1");
        textures[RSID_TEXTURE_LIGHT2] = loadTextureARGB(R.drawable.light2, "TLight2");
        textures[RSID_TEXTURE_FLARES] = loadTextureARGB(R.drawable.flares, "TFlares");

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
        mSampler = sampleBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);

        sampleBuilder = new Sampler.Builder(mRS);
        sampleBuilder.setMin(LINEAR);
        sampleBuilder.setMag(LINEAR);
        sampleBuilder.setWrapS(CLAMP);
        sampleBuilder.setWrapT(CLAMP);
        mLightSampler = sampleBuilder.create();

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
        mPfLighting = builder.create();
        mPfLighting.setName("PFLighting");
        mPfLighting.bindSampler(mLightSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");
        
        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsLights = builder.create();
        mPfsLights.setName("PFSLights");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        //mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);        

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        builder.setTextureMatrixEnable(true);
        mPvBackground = builder.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
    }
}

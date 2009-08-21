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
import android.renderscript.Type;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.NEAREST;
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

@SuppressWarnings({"FieldCanBeLocal"})
class GalaxyRS {
    private static final int GALAXY_RADIUS = 300;
    private static final int PARTICLES_COUNT = 12000;
    private static final float ELLIPSE_TWIST = 0.023333333f;

    private static final int RSID_STATE = 0;

    private static final int TEXTURES_COUNT = 3;
    private static final int RSID_TEXTURE_SPACE = 0;
    private static final int RSID_TEXTURE_LIGHT1 = 1;
    private static final int RSID_TEXTURE_FLARES = 2;

    private static final int RSID_PARTICLES = 1;
    private static final int PARTICLE_STRUCT_FIELDS_COUNT = 6;
    private static final int PARTICLE_STRUCT_ANGLE = 0;
    private static final int PARTICLE_STRUCT_DISTANCE = 1;
    private static final int PARTICLE_STRUCT_SPEED = 2;
    private static final int PARTICLE_STRUCT_RADIUS = 3;
    private static final int PARTICLE_STRUCT_S = 4;
    private static final int PARTICLE_STRUCT_T = 5;

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

    private Type mStateType;
    private Allocation mState;
    private Allocation mParticles;
    private Allocation mParticlesBuffer;
    private SimpleMesh mParticlesMesh;

    private final float[] mFloatData5 = new float[5];

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

    private void initRS() {
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
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
        createParticlesMesh();
        createParticles();
    }

    private void createParticlesMesh() {
        final Element.Builder elementBuilder = new Element.Builder(mRS);
        elementBuilder.addUNorm8RGBA();
        elementBuilder.addFloatXY();
        elementBuilder.addFloatST();
        final Element vertexElement = elementBuilder.create();

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(vertexElement, PARTICLES_COUNT * 3);
        meshBuilder.setPrimitive(Primitive.TRIANGLE);
        mParticlesMesh = meshBuilder.create();
        mParticlesMesh.setName("ParticlesMesh");

        mParticlesBuffer = mParticlesMesh.createVertexAllocation(vertexSlot);
        mParticlesBuffer.setName("ParticlesBuffer");
        mParticlesMesh.bindVertexAllocation(mParticlesBuffer, 0);
    }

    static class GalaxyState {
        public int frameCount;
        public int width;
        public int height;
        public int particlesCount;
        public int galaxyRadius;
    }

    private void createState() {
        GalaxyState state = new GalaxyState();
        state.width = mWidth;
        state.height = mHeight;
        state.particlesCount = PARTICLES_COUNT;
        state.galaxyRadius = GALAXY_RADIUS;

        mStateType = Type.createFromClass(mRS, GalaxyState.class, 1, "GalaxyState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(state);
    }

    private void createParticles() {
        final float[] particles = new float[PARTICLES_COUNT * PARTICLE_STRUCT_FIELDS_COUNT];

        int bufferIndex = 0;

        for (int i = 0; i < particles.length; i += PARTICLE_STRUCT_FIELDS_COUNT) {
            createParticle(particles, i, bufferIndex);
            bufferIndex += 3;            
        }

        mParticles = Allocation.createSized(mRS, USER_FLOAT, particles.length);
        mParticles.data(particles);
    }

    @SuppressWarnings({"PointlessArithmeticExpression"})
    private void createParticle(float[] particles, int index, int bufferIndex) {
        float d = abs(randomGauss()) * GALAXY_RADIUS / 2.0f + random(-4.0f, 4.0f);
        float z = randomGauss() * 0.5f * 0.8f * ((GALAXY_RADIUS - d) / (float) GALAXY_RADIUS);
        z += 1.0f;
        float p = d * ELLIPSE_TWIST;

        particles[index + PARTICLE_STRUCT_ANGLE] = random(0.0f, (float) (Math.PI * 2.0));
        particles[index + PARTICLE_STRUCT_DISTANCE] = d;
        particles[index + PARTICLE_STRUCT_SPEED] = random(0.0015f, 0.0025f) *
                (0.5f + (0.5f * (float) GALAXY_RADIUS / d)) * 0.7f;
        particles[index + PARTICLE_STRUCT_RADIUS] = z * random(1.2f, 2.1f);
        particles[index + PARTICLE_STRUCT_S] = (float) Math.cos(p);
        particles[index + PARTICLE_STRUCT_T] = (float) Math.sin(p);
        
        int red, green, blue;
        if (d < GALAXY_RADIUS / 3.0f) {
            red = (int) (220 + (d / (float) GALAXY_RADIUS) * 35);
            green = 220;
            blue = 220;
        } else {
            red = 180;
            green = 180;
            blue = (int) constrain(140 + (d / (float) GALAXY_RADIUS) * 115, 140, 255);
        }
        
        final int color = red | green << 8 | blue << 16 | 0xff000000;

        final float[] floatData = mFloatData5;
        final Allocation buffer = mParticlesBuffer;
        
        floatData[0] = Float.intBitsToFloat(color);
        floatData[3] = 0.0f;
        floatData[4] = 1.0f;
        buffer.subData1D(bufferIndex, 1, floatData);

        bufferIndex++;
        floatData[3] = 1.0f;
        floatData[4] = 1.0f;
        buffer.subData1D(bufferIndex, 1, floatData);

        bufferIndex++;
        floatData[3] = 0.5f;
        floatData[4] = 0.0f;
        buffer.subData1D(bufferIndex, 1, floatData);
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
        sampleBuilder.setMin(NEAREST);
        sampleBuilder.setMag(NEAREST);
        sampleBuilder.setWrapS(WRAP);
        sampleBuilder.setWrapT(WRAP);
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
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        builder.setDitherEnable(false);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");
        
        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        mPfsLights = builder.create();
        mPfsLights.setName("PFSLights");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        //mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);        

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        mPvBackground = builder.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
    }
}

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

package com.android.grass.rs;

import android.content.res.Resources;
import static android.renderscript.RenderScript.SamplerParam.*;
import static android.renderscript.RenderScript.SamplerValue.*;
import static android.renderscript.RenderScript.EnvMode.*;
import static android.renderscript.RenderScript.DepthFunc.*;
import static android.renderscript.RenderScript.BlendSrcFunc;
import static android.renderscript.RenderScript.BlendDstFunc;

import android.renderscript.RenderScript;
import android.renderscript.Element;

import java.util.TimeZone;

class GrassRS {
    private static final int RSID_STATE = 0;
    private static final int RSID_SKY_TEXTURES = 1;
    private static final int SKY_TEXTURES_COUNT = 4;

    private Resources mResources;
    private RenderScript mRS;

    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.Script mScript;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.Sampler mSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.ProgramFragment mPfBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.ProgramFragmentStore mPfsBackground;

    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.Allocation mSkyTexturesIDs;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.Allocation[] mSkyTextures;
    @SuppressWarnings({"FieldCanBeLocal"})
    private int[] mSkyBufferIDs;
    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript.Allocation mState;

    public GrassRS() {
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
        
        mRS.scriptCBegin();
        mRS.scriptCSetClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mRS.scriptCSetScript(mResources, R.raw.grass);
        mRS.scriptCSetTimeZone(TimeZone.getDefault().getID());
        mRS.scriptCSetRoot(true);

        mScript = mRS.scriptCCreate();

        loadSkyTextures();        
        mScript.bindAllocation(mState, RSID_STATE);
        mScript.bindAllocation(mSkyTexturesIDs, RSID_SKY_TEXTURES);

        mRS.contextBindRootScript(mScript);        
    }

    private void createScriptStructures() {
        mState = mRS.allocationCreateSized(Element.USER_I32, 1);    
        mState.data(new int[1]);
    }

    private void loadSkyTextures() {
        mSkyBufferIDs = new int[SKY_TEXTURES_COUNT];
        mSkyTextures = new RenderScript.Allocation[SKY_TEXTURES_COUNT];
        mSkyTexturesIDs = mRS.allocationCreateSized(
                Element.USER_FLOAT, SKY_TEXTURES_COUNT);

        final RenderScript.Allocation[] textures = mSkyTextures;
        textures[0] = loadTexture(R.drawable.night, "night");
        textures[1] = loadTexture(R.drawable.sunrise, "sunrise");
        textures[2] = loadTexture(R.drawable.sky, "sky");
        textures[3] = loadTexture(R.drawable.sunset, "sunset");

        final int[] bufferIds = mSkyBufferIDs;
        final int count = textures.length;

        for (int i = 0; i < count; i++) {
            final RenderScript.Allocation texture = textures[i];
            texture.uploadToTexture(0);
            bufferIds[i] = texture.getID();
        }

        mSkyTexturesIDs.data(bufferIds);
    }

    private RenderScript.Allocation loadTexture(int id, String name) {
        RenderScript.Allocation allocation = mRS.allocationCreateFromBitmapResource(mResources, id,
                Element.RGB_565, false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        mRS.samplerBegin();
        mRS.samplerSet(FILTER_MIN, LINEAR);
        mRS.samplerSet(FILTER_MAG, LINEAR);
        mRS.samplerSet(WRAP_MODE_S, CLAMP);
        mRS.samplerSet(WRAP_MODE_T, CLAMP);
        mSampler = mRS.samplerCreate();

        mRS.programFragmentBegin(null, null);
        mRS.programFragmentSetTexEnable(0, true);
        mRS.programFragmentSetTexEnvMode(0, REPLACE);
        mPfBackground = mRS.programFragmentCreate();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        mRS.programFragmentStoreBegin(null, null);
        mRS.programFragmentStoreDepthFunc(ALWAYS);
        mRS.programFragmentStoreBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        mRS.programFragmentStoreDitherEnable(true);
        mRS.programFragmentStoreDepthMask(false);
        mPfsBackground = mRS.programFragmentStoreCreate();
        mPfsBackground.setName("PFSBackground");
    }

    private void createProgramVertex() {
    }
}

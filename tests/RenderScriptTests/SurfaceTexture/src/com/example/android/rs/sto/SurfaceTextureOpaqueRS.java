/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.example.android.rs.sto;

import android.content.res.Resources;
import android.renderscript.*;
import android.graphics.SurfaceTexture;
import android.util.Log;


public class SurfaceTextureOpaqueRS {
    static final private int NUM_CAMERA_PREVIEW_BUFFERS = 2;

    public SurfaceTextureOpaqueRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_sto mScript;
    private SurfaceTexture mST;
    private Allocation mSto;
    private Allocation mSto2;
    private Allocation mRto;
    private ProgramFragment mPF;

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;

        Type.Builder tb = new Type.Builder(mRS, Element.RGBA_8888(mRS));
        tb.setX(640);
        tb.setY(480);
        mSto = Allocation.createTyped(mRS, tb.create(), Allocation.USAGE_GRAPHICS_TEXTURE |
                                                 Allocation.USAGE_IO_INPUT);
        mRto = Allocation.createTyped(mRS, tb.create(), Allocation.USAGE_GRAPHICS_RENDER_TARGET |
                                                 Allocation.USAGE_IO_OUTPUT);
        mSto2 = Allocation.createTyped(mRS, tb.create(), Allocation.USAGE_GRAPHICS_TEXTURE |
                                                 Allocation.USAGE_IO_INPUT);
        mST = mSto.getSurfaceTexture();
        mRto.setSurfaceTexture(mSto2.getSurfaceTexture());

        ProgramFragmentFixedFunction.Builder pfb = new ProgramFragmentFixedFunction.Builder(rs);
        pfb.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                       ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mPF = pfb.create();
        mPF.bindSampler(Sampler.CLAMP_NEAREST(mRS), 0);
        rs.bindProgramFragment(mPF);

        mScript = new ScriptC_sto(mRS, mRes, R.raw.sto);
        mScript.set_sto(mSto);
        mScript.set_rto(mRto);
        mScript.set_sto2(mSto2);
        mScript.set_pf(mPF);

        mRS.bindRootScript(mScript);


        android.util.Log.v("sto", "Init complete");
    }

    SurfaceTexture getST() {
        return mST;
    }

    public void newFrame() {
        mSto.ioReceive();
    }

}

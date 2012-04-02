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


import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.util.Log;

public class SurfaceTextureOpaqueView extends RSSurfaceView {

    public SurfaceTextureOpaqueView(Context context) {
        super(context);
    }

    RenderScriptGL mRS;
    SurfaceTextureOpaqueRS mRender;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mRS != null) {
            mRS = null;
            destroyRenderScriptGL();
        }
    }

    SurfaceTexture getST() {
        RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
        mRS = createRenderScriptGL(sc);
        mRender = new SurfaceTextureOpaqueRS();
        mRender.init(mRS, getResources());
        return mRender.getST();
    }

}



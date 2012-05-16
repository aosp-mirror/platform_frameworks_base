/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.example.android.rs.miscsamples;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class RsRenderStatesView extends RSSurfaceView {

    public RsRenderStatesView(Context context) {
        super(context);
        ensureRenderScript();
    }

    private RenderScriptGL mRS;
    private RsRenderStatesRS mRender;

    private void ensureRenderScript() {
        if (mRS == null) {
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            sc.setDepth(16, 24);
            mRS = createRenderScriptGL(sc);
            mRender = new RsRenderStatesRS();
            mRender.init(mRS, getResources());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureRenderScript();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        mRender.surfaceChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        mRender = null;
        if (mRS != null) {
            mRS = null;
            destroyRenderScriptGL();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mRender.onActionDown((int)ev.getX(), (int)ev.getY());
            return true;
        }

        return false;
    }
}



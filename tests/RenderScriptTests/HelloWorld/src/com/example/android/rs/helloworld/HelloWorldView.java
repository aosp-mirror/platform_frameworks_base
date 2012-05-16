/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.example.android.rs.helloworld;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;

import android.content.Context;
import android.view.MotionEvent;

public class HelloWorldView extends RSSurfaceView {
    // Renderscipt context
    private RenderScriptGL mRS;
    // Script that does the rendering
    private HelloWorldRS mRender;

    public HelloWorldView(Context context) {
        super(context);
        ensureRenderScript();
    }

    private void ensureRenderScript() {
        if (mRS == null) {
            // Initialize renderscript with desired surface characteristics.
            // In this case, just use the defaults
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            mRS = createRenderScriptGL(sc);
            // Create an instance of the script that does the rendering
            mRender = new HelloWorldRS();
            mRender.init(mRS, getResources());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureRenderScript();
    }

    @Override
    protected void onDetachedFromWindow() {
        // Handle the system event and clean up
        mRender = null;
        if (mRS != null) {
            mRS = null;
            destroyRenderScriptGL();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Pass touch events from the system to the rendering script
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mRender.onActionDown((int)ev.getX(), (int)ev.getY());
            return true;
        }

        return false;
    }
}



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

package com.android.modelviewer;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;
import android.renderscript.RenderScriptGL;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;

public class SceneGraphView extends RSSurfaceView {

    public SceneGraphView(Context context) {
        super(context);
        //setFocusable(true);
    }

    private RenderScriptGL mRS;
    private SceneGraphRS mRender;


    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        if (mRS == null) {
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            sc.setDepth(16, 24);
            mRS = createRenderScriptGL(sc);
            mRS.setSurface(holder, w, h);
            mRender = new SceneGraphRS();
            mRender.init(mRS, getResources(), w, h);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mRS != null) {
            mRS = null;
            destroyRenderScriptGL();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        // break point at here
        // this method doesn't work when 'extends View' include 'extends ScrollView'.
        return super.onKeyDown(keyCode, event);
    }


    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        boolean ret = true;
        int act = ev.getAction();
        if (act == ev.ACTION_UP) {
            ret = false;
        }

        mRender.touchEvent((int)ev.getX(), (int)ev.getY());
        return ret;
    }
}



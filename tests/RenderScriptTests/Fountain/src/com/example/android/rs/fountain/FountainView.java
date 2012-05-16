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

package com.example.android.rs.fountain;

import java.io.Writer;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.renderscript.RSTextureView;
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

public class FountainView extends RSTextureView {

    public FountainView(Context context) {
        super(context);
        //setFocusable(true);
    }

    private RenderScriptGL mRS;
    private FountainRS mRender;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        android.util.Log.e("rs", "onAttachedToWindow");
        if (mRS == null) {
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            mRS = createRenderScriptGL(sc);
            mRender = new FountainRS();
            mRender.init(mRS, getResources());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        android.util.Log.e("rs", "onDetachedFromWindow");
        if (mRS != null) {
            mRS = null;
            destroyRenderScriptGL();
        }
    }


    @Override
    public boolean onTouchEvent(MotionEvent ev)
    {
        int act = ev.getActionMasked();
        if (act == ev.ACTION_UP) {
            mRender.newTouchPosition(0, 0, 0, ev.getPointerId(0));
            return false;
        } else if (act == MotionEvent.ACTION_POINTER_UP) {
            // only one pointer going up, we can get the index like this
            int pointerIndex = ev.getActionIndex();
            int pointerId = ev.getPointerId(pointerIndex);
            mRender.newTouchPosition(0, 0, 0, pointerId);
        }
        int count = ev.getHistorySize();
        int pcount = ev.getPointerCount();

        for (int p=0; p < pcount; p++) {
            int id = ev.getPointerId(p);
            mRender.newTouchPosition(ev.getX(p),
                                     ev.getY(p),
                                     ev.getPressure(p),
                                     id);

            for (int i=0; i < count; i++) {
                mRender.newTouchPosition(ev.getHistoricalX(p, i),
                                         ev.getHistoricalY(p, i),
                                         ev.getHistoricalPressure(p, i),
                                         id);
            }
        }
        return true;
    }
}



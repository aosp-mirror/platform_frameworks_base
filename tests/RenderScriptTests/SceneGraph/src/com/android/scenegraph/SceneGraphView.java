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

package com.android.scenegraph;

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
import android.view.ScaleGestureDetector;

public class SceneGraphView extends RSSurfaceView {

    public SceneGraphView(Context context) {
        super(context);
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    private RenderScriptGL mRS;
    SceneGraphRS mRender;

    private ScaleGestureDetector mScaleDetector;
    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;

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
            mRender = null;
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
    public boolean onTouchEvent(MotionEvent ev) {
        mScaleDetector.onTouchEvent(ev);

        boolean ret = false;
        float x = ev.getX();
        float y = ev.getY();

        final int action = ev.getAction();

        switch (action & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN: {
            mRender.onActionDown(x, y);
            mActivePointerId = ev.getPointerId(0);
            ret = true;
            break;
        }
        case MotionEvent.ACTION_MOVE: {
            if (!mScaleDetector.isInProgress()) {
                mRender.onActionMove(x, y);
            }
            mRender.onActionDown(x, y);
            ret = true;
            break;
        }

        case MotionEvent.ACTION_UP: {
            mActivePointerId = INVALID_POINTER_ID;
            break;
        }

        case MotionEvent.ACTION_CANCEL: {
            mActivePointerId = INVALID_POINTER_ID;
            break;
        }

        case MotionEvent.ACTION_POINTER_UP: {
            final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK)
                    >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
            final int pointerId = ev.getPointerId(pointerIndex);
            if (pointerId == mActivePointerId) {
                // This was our active pointer going up. Choose a new
                // active pointer and adjust accordingly.
                final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                x = ev.getX(newPointerIndex);
                y = ev.getY(newPointerIndex);
                mRender.onActionDown(x, y);
                mActivePointerId = ev.getPointerId(newPointerIndex);
            }
            break;
        }
        }

        return ret;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mRender.onActionScale(detector.getScaleFactor());
            return true;
        }
    }
}



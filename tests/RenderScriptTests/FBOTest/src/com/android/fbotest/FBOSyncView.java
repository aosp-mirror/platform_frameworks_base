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

package com.android.fbotest;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ScaleGestureDetector;
import android.util.Log;

public class FBOSyncView extends RSSurfaceView {

    private RenderScriptGL mRS;
    private FBOSyncRS mRender;

    private ScaleGestureDetector mScaleDetector;

    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;

    public FBOSyncView(Context context) {
        super(context);
        ensureRenderScript();
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    private void ensureRenderScript() {
        if (mRS == null) {
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            sc.setDepth(16, 24);
            mRS = createRenderScriptGL(sc);
            mRender = new FBOSyncRS();
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

    public void loadA3DFile(String path) {
        mRender.loadA3DFile(path);
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



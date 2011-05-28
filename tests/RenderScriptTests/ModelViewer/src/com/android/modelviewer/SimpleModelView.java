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

package com.android.modelviewer;

import android.renderscript.Matrix4f;
import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScriptGL;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ScaleGestureDetector;
import android.util.Log;

public class SimpleModelView extends RSSurfaceView implements SensorEventListener {

    private RenderScriptGL mRS;
    private SimpleModelRS mRender;

    private ScaleGestureDetector mScaleDetector;

    private SensorManager mSensorManager;
    private Sensor mRotationVectorSensor;
    private final float[] mRotationMatrix = new float[16];

    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;
    private boolean mUseSensor = false;
    private Matrix4f mIdentityMatrix = new Matrix4f();

    public SimpleModelView(Context context) {
        super(context);
        ensureRenderScript();
        mScaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        // Get an instance of the SensorManager
        mSensorManager = (SensorManager)getContext().getSystemService(Context.SENSOR_SERVICE);
        // find the rotation-vector sensor
        mRotationVectorSensor = mSensorManager.getDefaultSensor(
                Sensor.TYPE_ROTATION_VECTOR);
        mIdentityMatrix.loadIdentity();
    }

    private void ensureRenderScript() {
        if (mRS == null) {
            RenderScriptGL.SurfaceConfig sc = new RenderScriptGL.SurfaceConfig();
            sc.setDepth(16, 24);
            mRS = createRenderScriptGL(sc);
            mRender = new SimpleModelRS();
            mRender.init(mRS, getResources());
        }
    }

    @Override
    public void resume() {
        mSensorManager.registerListener(this, mRotationVectorSensor, 10000);
    }

    @Override
    public void pause() {
        mSensorManager.unregisterListener(this);
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

    public void onSensorChanged(SensorEvent event) {
        // we received a sensor event. it is a good practice to check
        // that we received the proper event
        if (mUseSensor) {
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                // convert the rotation-vector to a 4x4 matrix. the matrix
                // is interpreted by Open GL as the inverse of the
                // rotation-vector, which is what we want.
                SensorManager.getRotationMatrixFromVector(
                        mRotationMatrix , event.values);

                if (mRender != null) {
                    mRender.onPostureChanged(new Matrix4f(mRotationMatrix));
                }
            }
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public void toggleSensor() {
        mUseSensor = !mUseSensor;
        if (mUseSensor == false) {
            mRender.onPostureChanged(mIdentityMatrix);
        }
    }
}

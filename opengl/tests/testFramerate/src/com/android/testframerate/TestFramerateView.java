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

package com.android.testframerate;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLES20;

class TestFramerateView extends GLSurfaceView {
    private static String TAG = "TestFramerateView";

    public TestFramerateView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setRenderer(new Renderer());
    }

    private long mLastTime_us = 0;
    private long mNumShortFramesElapsed = 0;
    private void registerTime(long now_us) {
        long longFrameTime_ms = Integer.parseInt(SystemProperties.get("debug.longframe_ms", "16"));
        long elapsedTime_us = now_us - mLastTime_us;
        float fps = 1000000.f / elapsedTime_us;
        if (mLastTime_us > 0 && elapsedTime_us > longFrameTime_ms*1000) {
          Log.v(TAG, "Long frame: " + elapsedTime_us/1000.f + " ms (" + fps + " fps)");
          if (mNumShortFramesElapsed > 0) {
            Log.v(TAG, "  Short frames since last long frame: " + mNumShortFramesElapsed);
            mNumShortFramesElapsed = 0;
          }
        } else {
            ++mNumShortFramesElapsed;
        }

        mLastTime_us = now_us;
    }

    private class Renderer implements GLSurfaceView.Renderer {
        public Renderer() {
        }


        public void onDrawFrame(GL10 gl) {
            long now_us = System.nanoTime() / 1000;
            registerTime(now_us);

            float red = (now_us % 1000000) / 1000000.f;
            float green = (now_us % 2000000) / 2000000.f;
            float blue = (now_us % 3000000) / 3000000.f;
            GLES20.glClearColor(red, green, blue, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        }

    }
}

/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.gameperformance;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

public class CustomOpenGLView extends GLSurfaceView {
    public final static String TAG = "CustomOpenGLView";

    private final List<Long> mFrameTimes;
    private final Object mLock = new Object();
    private boolean mRenderReady = false;
    private FrameDrawer mFrameDrawer = null;

    private float mRenderRatio;
    private int mRenderWidth;
    private int mRenderHeight;

    public interface FrameDrawer {
        public void drawFrame(@NonNull GL10 gl);
    }

    public CustomOpenGLView(@NonNull Context context) {
        super(context);

        mFrameTimes = new ArrayList<Long>();

        setEGLContextClientVersion(2);

        setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                Log.i(TAG, "SurfaceCreated: " + config);
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
                gl.glClearDepthf(1.0f);
                gl.glDisable(GL10.GL_DEPTH_TEST);
                gl.glDepthFunc(GL10.GL_LEQUAL);

                gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT,
                          GL10.GL_NICEST);
                synchronized (mLock) {
                    mRenderReady = true;
                    mLock.notify();
                }
            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                Log.i(TAG, "SurfaceChanged: " + width + "x" + height);
                GLES20.glViewport(0, 0, width, height);
                setRenderBounds(width, height);
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                GLES20.glClearColor(0.25f, 0.25f, 0.25f, 1.0f);
                gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
                synchronized (mLock) {
                    if (mFrameDrawer != null) {
                        mFrameDrawer.drawFrame(gl);
                    }
                    mFrameTimes.add(System.currentTimeMillis());
                }
            }
        });
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    public void setRenderBounds(int width, int height) {
        mRenderWidth = width;
        mRenderHeight = height;
        mRenderRatio = (float) mRenderWidth / mRenderHeight;
    }

    public float getRenderRatio() {
        return mRenderRatio;
    }

    public int getRenderWidth() {
        return mRenderWidth;
    }

    public int getRenderHeight() {
        return mRenderHeight;
    }

    /**
     * Resets frame times in order to calculate FPS for the different test pass.
     */
    public void resetFrameTimes() {
        synchronized (mLock) {
            mFrameTimes.clear();
        }
    }

    /**
     * Returns current FPS based on collected frame times.
     */
    public double getFps() {
        synchronized (mLock) {
            if (mFrameTimes.size() < 2) {
                return 0.0f;
            }
            return 1000.0 * mFrameTimes.size() /
                    (mFrameTimes.get(mFrameTimes.size() - 1) - mFrameTimes.get(0));
        }
    }

    /**
     * Waits for render attached to the view.
     */
    public void waitRenderReady() {
        synchronized (mLock) {
            while (!mRenderReady) {
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Sets/resets frame drawer.
     */
    public void setFrameDrawer(@Nullable FrameDrawer frameDrawer) {
        mFrameDrawer = frameDrawer;
    }
}

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
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

public class CustomOpenGLView extends GLSurfaceView {
    private Random mRandom;
    private List<Long> mFrameTimes;

    public CustomOpenGLView(Context context) {
        super(context);

        mRandom = new Random();
        mFrameTimes = new ArrayList<Long>();

        setEGLContextClientVersion(2);

        setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
                gl.glClearDepthf(1.0f);
                gl.glEnable(GL10.GL_DEPTH_TEST);
                gl.glDepthFunc(GL10.GL_LEQUAL);

                gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT,
                          GL10.GL_NICEST);            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                GLES20.glViewport(0, 0, width, height);
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                GLES20.glClearColor(
                        mRandom.nextFloat(), mRandom.nextFloat(), mRandom.nextFloat(), 1.0f);
                gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);
                synchronized (mFrameTimes) {
                    mFrameTimes.add(System.currentTimeMillis());
                }
            }
        });
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    /**
     * Resets frame times in order to calculate fps for different test pass.
     */
    public void resetFrameTimes() {
        synchronized (mFrameTimes) {
            mFrameTimes.clear();
        }
    }

    /**
     * Returns current fps based on collected frame times.
     */
    public double getFps() {
        synchronized (mFrameTimes) {
            if (mFrameTimes.size() < 2) {
                return 0.0f;
            }
            return 1000.0 * mFrameTimes.size() /
                    (mFrameTimes.get(mFrameTimes.size() - 1) - mFrameTimes.get(0));
        }
    }
}

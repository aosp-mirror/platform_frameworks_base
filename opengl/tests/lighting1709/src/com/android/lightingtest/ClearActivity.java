/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.lightingtest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

public class ClearActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGLView = new ClearGLSurfaceView(this);
        setContentView(mGLView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mGLView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGLView.onResume();
    }
    private GLSurfaceView mGLView;
}

class ClearGLSurfaceView extends GLSurfaceView {
    public ClearGLSurfaceView(Context context) {
        super(context);
        mRenderer = new ClearRenderer();
        setRenderer(mRenderer);
    }

    ClearRenderer mRenderer;
}

class ClearRenderer implements GLSurfaceView.Renderer {
    public ClearRenderer() {
    }

    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Do nothing special.
    }

    public void onSurfaceChanged(GL10 gl, int w, int h) {
        // Compute the projection matrix
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();

        // Compute the boundaries of the frustum
        float fl = (float) (-(w / 2)) / 288;
        float fr = (float) (w / 2) / 288;
        float ft = (float) (h / 2) / 288;
        float fb = (float) (-(h / 2)) / 288;

        // Set the view frustum
        gl.glFrustumf(fl, fr, fb, ft, 1.0f, 2000.0f);

        // Set the viewport dimensions
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
        gl.glViewport(0, 0, w, h);
    }

    public void onDrawFrame(GL10 gl) {
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);

        final float lightOff[]        = {0.0f, 0.0f,  0.0f, 1.0f};
        final float lightAmbient[]    = {5.0f, 0.0f,  0.0f, 1.0f};
        final float lightDiffuse[]    = {0.0f, 2.0f,  0.0f, 0.0f};
        final float lightPosSpot[]    = {0.0f, 0.0f, -8.0f, 1.0f};

        final float pos[] = {
                    -5.0f, -1.5f, 0.0f,
                     0.0f, -1.5f, 0.0f,
                     5.0f, -1.5f, 0.0f,
                };
        
        final float v[] = new float[9];
        ByteBuffer vbb = ByteBuffer.allocateDirect(v.length*4);
        vbb.order(ByteOrder.nativeOrder());
        FloatBuffer vb = vbb.asFloatBuffer();

        gl.glDisable(GL10.GL_DITHER);

        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT, lightAmbient, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE, lightDiffuse, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_SPECULAR, lightOff, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, lightPosSpot, 0);
        gl.glEnable(GL10.GL_LIGHT0);
        
        gl.glEnable(GL10.GL_LIGHTING);


        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glNormal3f(0, 0, 1);
        

        // draw first 3 triangles, without using transforms
        for (int i=0 ; i<3 ; i++) {
            v[0] = -1; v[1] =-1; v[2] = -10;
            v[3] =  0; v[4] = 1; v[5] = -10;
            v[6] =  1; v[7] =-1; v[8] = -10;
            for (int j=0 ; j<3 ; j++) {
                v[j*3+0] -= pos[i*3+0];
                v[j*3+1] -= pos[i*3+1];
                v[j*3+2] -= pos[i*3+2];
            }
            vb.put(v).position(0);
            gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
            gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        }
        
        // draw the 2nd batch this time with transforms
        v[0] = -1; v[1] =-1; v[2] = -10;
        v[3] =  0; v[4] = 1; v[5] = -10;
        v[6] =  1; v[7] =-1; v[8] = -10;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);

        // draw lower left triangle
        gl.glPushMatrix();
        gl.glTranslatef(pos[0], pos[1], pos[2]);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();

        // draw lower middle triangle
        gl.glPushMatrix();
        gl.glTranslatef(pos[3], pos[4], pos[5]);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();

        // draw lower right triangle
        gl.glPushMatrix();
        gl.glTranslatef(pos[6], pos[7], pos[8]);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();      
    }

    public int[] getConfigSpec() {
        int[] configSpec = { EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE };
        return configSpec;      
    }
}


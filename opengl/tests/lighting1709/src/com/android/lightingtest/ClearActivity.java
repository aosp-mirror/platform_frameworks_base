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
        instance = counter++;
        Log.e("ClearActivity", ":::::: onCreate: instance" + instance + " is created");
        super.onCreate(savedInstanceState);
        mGLView = new ClearGLSurfaceView(this);
        setContentView(mGLView);
    }

    @Override
    protected void onPause() {
        Log.e("ClearActivity", ":::::: instance" + instance + " onPause: is called");
        super.onPause();
        mGLView.onPause();
    }

    @Override
    protected void onResume() {
        Log.e("ClearActivity", ":::::: instance" + instance + " onResume: is called");
        super.onResume();
        mGLView.onResume();
    }

    @Override
    protected void onStop() {
        Log.e("ClearActivity", ":::::: instance" + instance + " onStop: is called");
        super.onStop();        
    }

    @Override
    protected void onDestroy() {
        Log.e("ClearActivity", ":::::: instance" + instance + " onDestroy: is called");
        super.onDestroy();      
    }

    private GLSurfaceView mGLView;

    private static int counter = 0;
    private int        instance;
}

class ClearGLSurfaceView extends GLSurfaceView {
    public ClearGLSurfaceView(Context context) {
        super(context);
        instance = counter++;
        Log.e("ClearGLSurfaceView", ":::::: instance" + instance + " is created");
        mRenderer = new ClearRenderer();
        setRenderer(mRenderer);
    }

    public boolean onTouchEvent(final MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {// falling through on purpose here
                Log.e("ClearGLSurfaceView", ":::::: instance" + instance + " onTouchEvent: handling down or move action");
                queueEvent(new Runnable(){
                    public void run() {
                        mRenderer.setColor(event.getX() / getWidth(),
                                event.getY() / getHeight(), 1.0f);
                    }}
                );
                return true;
            }
            case MotionEvent.ACTION_UP: {
                // launch a second instance of the same activity
                Log.e("ClearGLSurfaceView", ":::::: instance" + instance + " onTouchEvent: handling up action");
                //                      Intent intent = new Intent();
                //                      intent.setClass(getContext(), ClearActivity.class);
                //                      getContext().startActivity(intent);
            }

        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.e("ClearGLSurfaceView", ":::::: instance" + instance + " onDetachedFromWindow: is called");
        super.onDetachedFromWindow();                  
    }

    ClearRenderer mRenderer;

    private static int counter = 0;
    private int instance;
}

class ClearRenderer implements GLSurfaceView.Renderer {
    public ClearRenderer() {
        instance = counter++;
        Log.e("ClearRenderer", ":::::: instance" + instance + " is created");          
    }

    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Do nothing special.
        Log.e("ClearRenderer", ":::::: instance" + instance + " onSurfaceCreated: is called");            
    }

    public void onSurfaceChanged(GL10 gl, int w, int h) {
        Log.e("ClearRenderer", ":::::: instance" + instance + " onSurfaceChanged: is called");            

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
        //        gl.glClearColor(mRed, mGreen, mBlue, 1.0f);
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT | GL10.GL_DEPTH_BUFFER_BIT);

        float lightOff[]        = {0.0f, 0.0f, 0.0f, 1.0f};
        float lightAmbient[]    = {5.0f, 0.0f, 0.0f, 1.0f};
        float lightDiffuse[]    = {0.0f, 2.0f, 0.0f, 0.0f};
        float lightPosAmbient[] = {0.0f, 0.0f, 0.0f, 1.0f};
        float lightPosSpot[]    = {0.0f, 0.0f, -8.0f, 1.0f};

        
        float v[] = new float[9];
        ByteBuffer vbb = ByteBuffer.allocateDirect(v.length*4);
        vbb.order(ByteOrder.nativeOrder());
        FloatBuffer vb = vbb.asFloatBuffer();

        gl.glDisable(GL10.GL_DITHER);

        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_SPECULAR, lightOff, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_DIFFUSE, lightOff, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_AMBIENT, lightAmbient, 0);
        gl.glLightfv(GL10.GL_LIGHT0, GL10.GL_POSITION, lightPosAmbient, 0);
        gl.glEnable(GL10.GL_LIGHT0);

        gl.glLightfv(GL10.GL_LIGHT1, GL10.GL_SPECULAR, lightOff, 0);
        gl.glLightfv(GL10.GL_LIGHT1, GL10.GL_DIFFUSE, lightDiffuse, 0);
        gl.glLightfv(GL10.GL_LIGHT1, GL10.GL_AMBIENT, lightOff, 0);
        gl.glLightfv(GL10.GL_LIGHT1, GL10.GL_POSITION, lightPosSpot, 0);
        gl.glLightf(GL10.GL_LIGHT1, GL10.GL_CONSTANT_ATTENUATION, 1.0f);
        gl.glLightf(GL10.GL_LIGHT1, GL10.GL_LINEAR_ATTENUATION, 0.0f);
        gl.glLightf(GL10.GL_LIGHT1, GL10.GL_QUADRATIC_ATTENUATION, 0.022f);
        gl.glEnable(GL10.GL_LIGHT1);

        gl.glEnable(GL10.GL_LIGHTING);

        // draw upper left triangle
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = -6f; v[1] = 0.5f; v[2] = -10f;
        v[3] = -5f; v[4] = 2.5f; v[5] = -10f;
        v[6] = -4f; v[7] = 0.5f; v[8] = -10f;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);

        // draw upper middle triangle
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = -1f; v[1] = 0.5f; v[2] = -10f;
        v[3] = 0f; v[4] = 2.5f; v[5] = -10f;
        v[6] = 1f; v[7] = 0.5f; v[8] = -10f;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);

        // draw upper right triangle
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = 4f; v[1] = 0.5f; v[2] = -10f;
        v[3] = 5f; v[4] = 2.5f; v[5] = -10f;
        v[6] = 6f; v[7] = 0.5f; v[8] = -10f;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);

        // draw lower left triangle
        gl.glPushMatrix();
        gl.glTranslatef(-5.0f, -1.5f, 0.0f);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = -1; v[1] = -1; v[2] = -10;
        v[3] = 0; v[4] = 1; v[5] = -10;
        v[6] = 1; v[7] = -1; v[8] = -10;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();

        // draw lower middle triangle
        gl.glPushMatrix();
        gl.glTranslatef(0.0f, -1.5f, 0.0f);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = -1; v[1] = -1; v[2] = -10;
        v[3] = 0; v[4] = 1; v[5] = -10;
        v[6] = 1; v[7] = -1; v[8] = -10;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();

        // draw lower right triangle
        gl.glPushMatrix();
        gl.glTranslatef(5.0f, -1.5f, 0.0f);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        v[0] = -1; v[1] = -1; v[2] = -10;
        v[3] = 0; v[4] = 1; v[5] = -10;
        v[6] = 1; v[7] = -1; v[8] = -10;
        vb.put(v).position(0);
        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vb);
        gl.glNormal3f(0, 0, 1);
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 3);
        gl.glPopMatrix();      

    }

    public int[] getConfigSpec() {
        Log.e("ClearRenderer", ":::::: instance" + instance + " getConfigSpec: is called");              
        int[] configSpec = { EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE };
        return configSpec;      
    }

    public void setColor(float r, float g, float b) {
        Log.e("ClearRenderer", ":::::: instance" + instance + " setColor: is called");              
        mRed = r;
        mGreen = g;
        mBlue = b;
    }

    private float mRed;
    private float mGreen;
    private float mBlue;

    private static int counter = 0;
    private int instance;
}


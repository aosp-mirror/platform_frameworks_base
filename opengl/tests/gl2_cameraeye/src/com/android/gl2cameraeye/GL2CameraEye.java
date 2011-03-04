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

package com.android.gl2cameraeye;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.MotionEvent;
import android.content.Context;
import android.util.Log;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import android.graphics.SurfaceTexture;

import android.hardware.Camera;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.Sensor;

public class GL2CameraEye extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGLView = new CamGLSurfaceView(this);
        setContentView(mGLView);
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
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

class CamGLSurfaceView extends GLSurfaceView implements SensorEventListener {
    public CamGLSurfaceView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        mRenderer = new CamRenderer(context);
        setRenderer(mRenderer);

        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mAcceleration = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    }

    public boolean onTouchEvent(final MotionEvent event) {
        queueEvent(new Runnable(){
                public void run() {
                mRenderer.setPosition(event.getX() / getWidth(),
                                      event.getY() / getHeight());
            }});
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        mCamera.stopPreview();
        mCamera.release();

        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onResume() {
        mCamera = Camera.open();
        Camera.Parameters p = mCamera.getParameters();
        // No changes to default camera parameters
        mCamera.setParameters(p);

        queueEvent(new Runnable(){
                public void run() {
                    mRenderer.setCamera(mCamera);
                }});

        mSensorManager.registerListener(this, mAcceleration, SensorManager.SENSOR_DELAY_GAME);
        super.onResume();
    }

    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            final float[] accelerationVector = event.values;
            queueEvent(new Runnable(){
                    public void run() {
                        mRenderer.setAcceleration(accelerationVector);
                    }});
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Ignoring sensor accuracy changes.
    }

    CamRenderer mRenderer;
    Camera mCamera;

    SensorManager mSensorManager;
    Sensor mAcceleration;
}

class CamRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public CamRenderer(Context context) {
        mContext = context;

        mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
        Matrix.setIdentityM(mMMatrix, 0);

        float[] defaultAcceleration = {0.f,0.f,0.f};
        setAcceleration(defaultAcceleration);
        mPos[0] = 0.f;
        mPos[1] = 0.f;
        mPos[2] = 0.f;
        mVel[0] = 0.f;
        mVel[1] = 0.f;
        mVel[2] = 0.f;

    }

    /* The following set methods are not synchronized, so should only
     * be called within the rendering thread context. Use GLSurfaceView.queueEvent for safe access.
     */
    public void setPosition(float x, float y) {
        /* Map from screen (0,0)-(1,1) to scene coordinates */
        mPos[0] = (x*2-1)*mRatio;
        mPos[1] = (-y)*2+1;
        mPos[2] = 0.f;
        mVel[0] = 0;
        mVel[1] = 0;
        mVel[2] = 0;
    }

    public void setCamera(Camera camera) {
        mCamera = camera;
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        mCameraRatio = (float)previewSize.width/previewSize.height;
    }

    public void setAcceleration(float[] accelerationVector) {
        mGForce[0] = accelerationVector[0];
        mGForce[1] = accelerationVector[1];
        mGForce[2] = accelerationVector[2];
    }

    public void onDrawFrame(GL10 glUnused) {
        synchronized(this) {
            if (updateSurface) {
                mSurface.updateTexImage();

                mSurface.getTransformMatrix(mSTMatrix);
                long timestamp = mSurface.getTimestamp();
                doPhysics(timestamp);

                updateSurface = false;
            }
        }

        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
        GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        Matrix.multiplyMM(mMVPMatrix, 0, mVMatrix, 0, mMMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mMVPMatrix, 0);

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniform1f(muCRatioHandle, mCameraRatio);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
    }

    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
        GLES20.glViewport(0, 0, width, height);
        mRatio = (float) width / height;
        Matrix.frustumM(mProjMatrix, 0, -mRatio, mRatio, -1, 1, 3, 7);
    }

    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.

        /* Set up alpha blending and an Android background color */
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        /* Set up shaders and handles to their variables */
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return;
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }

        muCRatioHandle = GLES20.glGetUniformLocation(mProgram, "uCRatio");
        checkGlError("glGetUniformLocation uCRatio");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uCRatio");
        }

        /*
         * Create our texture. This has to be done each time the
         * surface is created.
         */

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

        // Can't do mipmapping with camera source
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        // Clamp to edge is the only option
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri mTextureID");

        /*
         * Create the SurfaceTexture that will feed this textureID, and pass it to the camera
         */

        mSurface = new SurfaceTexture(mTextureID);
        mSurface.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(mSurface);
        } catch (IOException t) {
            Log.e(TAG, "Cannot set preview texture target!");
        }

        /* Start the camera */
        mCamera.startPreview();

        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

        mLastTime = 0;

        synchronized(this) {
            updateSurface = false;
        }
    }

    synchronized public void onFrameAvailable(SurfaceTexture surface) {
        /* For simplicity, SurfaceTexture calls here when it has new
         * data available.  Call may come in from some random thread,
         * so let's be safe and use synchronize. No OpenGL calls can be done here.
         */
        updateSurface = true;
    }

    private void doPhysics(long timestamp) {
        /*
         * Move the camera surface around based on some simple spring physics with drag
         */

        if (mLastTime == 0)
            mLastTime = timestamp;

        float deltaT = (timestamp - mLastTime)/1000000000.f; // To seconds

        float springStrength = 20.f;
        float frictionCoeff = 10.f;
        float mass = 10.f;
        float gMultiplier = 4.f;
        /* Only update physics every 30 ms */
        if (deltaT > 0.030f) {
            mLastTime = timestamp;

            float[] totalForce = new float[3];
            totalForce[0] = -mPos[0] * springStrength - mVel[0]*frictionCoeff + gMultiplier*mGForce[0]*mass;
            totalForce[1] = -mPos[1] * springStrength - mVel[1]*frictionCoeff + gMultiplier*mGForce[1]*mass;
            totalForce[2] = -mPos[2] * springStrength - mVel[2]*frictionCoeff + gMultiplier*mGForce[2]*mass;

            float[] accel = new float[3];
            accel[0] = totalForce[0]/mass;
            accel[1] = totalForce[1]/mass;
            accel[2] = totalForce[2]/mass;

            /* Not a very accurate integrator */
            mVel[0] = mVel[0] + accel[0]*deltaT;
            mVel[1] = mVel[1] + accel[1]*deltaT;
            mVel[2] = mVel[2] + accel[2]*deltaT;

            mPos[0] = mPos[0] + mVel[0]*deltaT;
            mPos[1] = mPos[1] + mVel[1]*deltaT;
            mPos[2] = mPos[2] + mVel[2]*deltaT;

            Matrix.setIdentityM(mMMatrix, 0);
            Matrix.translateM(mMMatrix, 0, mPos[0], mPos[1], mPos[2]);
        }

    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private final float[] mTriangleVerticesData = {
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0, 0.f, 0.f,
        1.0f, -1.0f, 0, 1.f, 0.f,
        -1.0f,  1.0f, 0, 0.f, 1.f,
        1.0f,   1.0f, 0, 1.f, 1.f,
    };

    private FloatBuffer mTriangleVertices;

    private final String mVertexShader =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "uniform float uCRatio;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTextureCoord;\n" +
        "varying vec2 vTextureCoord;\n" +
        "varying vec2 vTextureNormCoord;\n" +
        "void main() {\n" +
        "  vec4 scaledPos = aPosition;\n" +
        "  scaledPos.x = scaledPos.x * uCRatio;\n" +
        "  gl_Position = uMVPMatrix * scaledPos;\n" +
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
        "  vTextureNormCoord = aTextureCoord.xy;\n" +
        "}\n";

    private final String mFragmentShader =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTextureCoord;\n" +
        "varying vec2 vTextureNormCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
        "  gl_FragColor.a = 1.0-min(length(vTextureNormCoord-0.5)*2.0,1.0);\n" +
        "}\n";

    private float[] mMVPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mMMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];

    private int mProgram;
    private int mTextureID;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int muCRatioHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    private float mRatio = 1.0f;
    private float mCameraRatio = 1.0f;
    private float[] mVel = new float[3];
    private float[] mPos = new float[3];
    private float[] mGForce = new float[3];

    private long mLastTime;

    private SurfaceTexture mSurface;
    private Camera mCamera;
    private boolean updateSurface = false;

    private Context mContext;
    private static String TAG = "CamRenderer";

    // Magic key
    private static int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
}

/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.camera2.legacy;

import android.graphics.ImageFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Environment;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.text.format.Time;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;
import android.os.SystemProperties;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A renderer class that manages the GL state, and can draw a frame into a set of output
 * {@link Surface}s.
 */
public class SurfaceTextureRenderer {
    private static final String TAG = SurfaceTextureRenderer.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142; // from EGL/eglext.h
    private static final int GL_MATRIX_SIZE = 16;
    private static final int VERTEX_POS_SIZE = 3;
    private static final int VERTEX_UV_SIZE = 2;
    private static final int EGL_COLOR_BITLENGTH = 8;
    private static final int GLES_VERSION = 2;
    private static final int PBUFFER_PIXEL_BYTES = 4;

    private static final int FLIP_TYPE_NONE = 0;
    private static final int FLIP_TYPE_HORIZONTAL = 1;
    private static final int FLIP_TYPE_VERTICAL = 2;
    private static final int FLIP_TYPE_BOTH = FLIP_TYPE_HORIZONTAL | FLIP_TYPE_VERTICAL;

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mConfigs;

    private class EGLSurfaceHolder {
        Surface surface;
        EGLSurface eglSurface;
        int width;
        int height;
    }

    private List<EGLSurfaceHolder> mSurfaces = new ArrayList<EGLSurfaceHolder>();
    private List<EGLSurfaceHolder> mConversionSurfaces = new ArrayList<EGLSurfaceHolder>();

    private ByteBuffer mPBufferPixels;

    // Hold this to avoid GC
    private volatile SurfaceTexture mSurfaceTexture;

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

    // Sampling is mirrored across the horizontal axis
    private static final float[] sHorizontalFlipTriangleVertices = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 1.f, 0.f,
            1.0f, -1.0f, 0, 0.f, 0.f,
            -1.0f,  1.0f, 0, 1.f, 1.f,
            1.0f,  1.0f, 0, 0.f, 1.f,
    };

    // Sampling is mirrored across the vertical axis
    private static final float[] sVerticalFlipTriangleVertices = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 1.f,
            1.0f, -1.0f, 0, 1.f, 1.f,
            -1.0f,  1.0f, 0, 0.f, 0.f,
            1.0f,  1.0f, 0, 1.f, 0.f,
    };

    // Sampling is mirrored across the both axes
    private static final float[] sBothFlipTriangleVertices = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 1.f, 1.f,
            1.0f, -1.0f, 0, 0.f, 1.f,
            -1.0f,  1.0f, 0, 1.f, 0.f,
            1.0f,  1.0f, 0, 0.f, 0.f,
    };

    // Sampling is 1:1 for a straight copy for the back camera
    private static final float[] sRegularTriangleVertices = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 0.f,
            1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  1.0f, 0, 0.f, 1.f,
            1.0f,  1.0f, 0, 1.f, 1.f,
    };

    private FloatBuffer mRegularTriangleVertices;
    private FloatBuffer mHorizontalFlipTriangleVertices;
    private FloatBuffer mVerticalFlipTriangleVertices;
    private FloatBuffer mBothFlipTriangleVertices;
    private final int mFacing;

    /**
     * As used in this file, this vertex shader maps a unit square to the view, and
     * tells the fragment shader to interpolate over it.  Each surface pixel position
     * is mapped to a 2D homogeneous texture coordinate of the form (s, t, 0, 1) with
     * s and t in the inclusive range [0, 1], and the matrix from
     * {@link SurfaceTexture#getTransformMatrix(float[])} is used to map this
     * coordinate to a texture location.
     */
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    /**
     * This fragment shader simply draws the color in the 2D texture at
     * the location from the {@code VERTEX_SHADER}.
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private float[] mMVPMatrix = new float[GL_MATRIX_SIZE];
    private float[] mSTMatrix = new float[GL_MATRIX_SIZE];

    private int mProgram;
    private int mTextureID = 0;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    private PerfMeasurement mPerfMeasurer = null;
    private static final String LEGACY_PERF_PROPERTY = "persist.camera.legacy_perf";

    public SurfaceTextureRenderer(int facing) {
        mFacing = facing;

        mRegularTriangleVertices = ByteBuffer.allocateDirect(sRegularTriangleVertices.length *
                FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mRegularTriangleVertices.put(sRegularTriangleVertices).position(0);

        mHorizontalFlipTriangleVertices = ByteBuffer.allocateDirect(
                sHorizontalFlipTriangleVertices.length * FLOAT_SIZE_BYTES).
                order(ByteOrder.nativeOrder()).asFloatBuffer();
        mHorizontalFlipTriangleVertices.put(sHorizontalFlipTriangleVertices).position(0);

        mVerticalFlipTriangleVertices = ByteBuffer.allocateDirect(
                sVerticalFlipTriangleVertices.length * FLOAT_SIZE_BYTES).
                order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVerticalFlipTriangleVertices.put(sVerticalFlipTriangleVertices).position(0);

        mBothFlipTriangleVertices = ByteBuffer.allocateDirect(
                sBothFlipTriangleVertices.length * FLOAT_SIZE_BYTES).
                order(ByteOrder.nativeOrder()).asFloatBuffer();
        mBothFlipTriangleVertices.put(sBothFlipTriangleVertices).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ":");
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            // TODO: handle this more gracefully
            throw new IllegalStateException("Could not compile shader " + shaderType);
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
        checkGlError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
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
            // TODO: handle this more gracefully
            throw new IllegalStateException("Could not link program");
        }
        return program;
    }

    private void drawFrame(SurfaceTexture st, int width, int height, int flipType)
            throws LegacyExceptionUtils.BufferQueueAbandonedException {
        checkGlError("onDrawFrame start");
        st.getTransformMatrix(mSTMatrix);

        Matrix.setIdentityM(mMVPMatrix, /*smOffset*/0);

        // Find intermediate buffer dimensions
        Size dimens;
        try {
            dimens = LegacyCameraDevice.getTextureSize(st);
        } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
            // Should never hit this.
            throw new IllegalStateException("Surface abandoned, skipping drawFrame...", e);
        }
        float texWidth = dimens.getWidth();
        float texHeight = dimens.getHeight();

        if (texWidth <= 0 || texHeight <= 0) {
            throw new IllegalStateException("Illegal intermediate texture with dimension of 0");
        }

        // Letterbox or pillar-box output dimensions into intermediate dimensions.
        RectF intermediate = new RectF(/*left*/0, /*top*/0, /*right*/texWidth, /*bottom*/texHeight);
        RectF output = new RectF(/*left*/0, /*top*/0, /*right*/width, /*bottom*/height);
        android.graphics.Matrix boxingXform = new android.graphics.Matrix();
        boxingXform.setRectToRect(output, intermediate, android.graphics.Matrix.ScaleToFit.CENTER);
        boxingXform.mapRect(output);

        // Find scaling factor from pillar-boxed/letter-boxed output dimensions to intermediate
        // buffer dimensions.
        float scaleX = intermediate.width() / output.width();
        float scaleY = intermediate.height() / output.height();

        // Intermediate texture is implicitly scaled to 'fill' the output dimensions in clip space
        // coordinates in the shader.  To avoid stretching, we need to scale the larger dimension
        // of the intermediate buffer so that the output buffer is actually letter-boxed
        // or pillar-boxed into the intermediate buffer after clipping.
        Matrix.scaleM(mMVPMatrix, /*offset*/0, /*x*/scaleX, /*y*/scaleY, /*z*/1);

        if (DEBUG) {
            Log.d(TAG, "Scaling factors (S_x = " + scaleX + ",S_y = " + scaleY + ") used for " +
                    width + "x" + height + " surface, intermediate buffer size is " + texWidth +
                    "x" + texHeight);
        }

        // Set viewport to be output buffer dimensions
        GLES20.glViewport(0, 0, width, height);

        if (DEBUG) {
            GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        }

        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

        FloatBuffer triangleVertices;
        switch(flipType) {
            case FLIP_TYPE_HORIZONTAL:
                triangleVertices = mHorizontalFlipTriangleVertices;
                break;
            case FLIP_TYPE_VERTICAL:
                triangleVertices = mVerticalFlipTriangleVertices;
                break;
            case FLIP_TYPE_BOTH:
                triangleVertices = mBothFlipTriangleVertices;
                break;
            default:
                triangleVertices = mRegularTriangleVertices;
                break;
        }

        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, VERTEX_POS_SIZE, GLES20.GL_FLOAT,
                /*normalized*/ false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        triangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, VERTEX_UV_SIZE, GLES20.GL_FLOAT,
                /*normalized*/ false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices);
        checkGlError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, /*count*/ 1, /*transpose*/ false, mMVPMatrix,
                /*offset*/ 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, /*count*/ 1, /*transpose*/ false, mSTMatrix,
                /*offset*/ 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /*offset*/ 0, /*count*/ 4);
        checkGlDrawError("glDrawArrays");
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    private void initializeGLState() {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (mProgram == 0) {
            throw new IllegalStateException("failed creating program");
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new IllegalStateException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new IllegalStateException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new IllegalStateException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new IllegalStateException("Could not get attrib location for uSTMatrix");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(/*n*/ 1, textures, /*offset*/ 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameter");
    }

    private int getTextureId() {
        return mTextureID;
    }

    private void clearState() {
        mSurfaces.clear();
        for (EGLSurfaceHolder holder : mConversionSurfaces) {
            try {
                LegacyCameraDevice.disconnectSurface(holder.surface);
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping...", e);
            }
        }
        mConversionSurfaces.clear();
        mPBufferPixels = null;
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
        }
        mSurfaceTexture = null;
    }

    private void configureEGLContext() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("No EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, /*offset*/ 0, version, /*offset*/ 1)) {
            throw new IllegalStateException("Cannot initialize EGL14");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, EGL_COLOR_BITLENGTH,
                EGL14.EGL_GREEN_SIZE, EGL_COLOR_BITLENGTH,
                EGL14.EGL_BLUE_SIZE, EGL_COLOR_BITLENGTH,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(mEGLDisplay, attribList, /*offset*/ 0, configs, /*offset*/ 0,
                configs.length, numConfigs, /*offset*/ 0);
        checkEglError("eglCreateContext RGB888+recordable ES2");
        mConfigs = configs[0];
        int[] attrib_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                attrib_list, /*offset*/ 0);
        checkEglError("eglCreateContext");
        if(mEGLContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("No EGLContext could be made");
        }
    }

    private void configureEGLOutputSurfaces(Collection<EGLSurfaceHolder> surfaces) {
        if (surfaces == null || surfaces.size() == 0) {
            throw new IllegalStateException("No Surfaces were provided to draw to");
        }
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        for (EGLSurfaceHolder holder : surfaces) {
            holder.eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mConfigs,
                    holder.surface, surfaceAttribs, /*offset*/ 0);
            checkEglError("eglCreateWindowSurface");
        }
    }

    private void configureEGLPbufferSurfaces(Collection<EGLSurfaceHolder> surfaces) {
        if (surfaces == null || surfaces.size() == 0) {
            throw new IllegalStateException("No Surfaces were provided to draw to");
        }

        int maxLength = 0;
        for (EGLSurfaceHolder holder : surfaces) {
            int length = holder.width * holder.height;
            // Find max surface size, ensure PBuffer can hold this many pixels
            maxLength = (length > maxLength) ? length : maxLength;
            int[] surfaceAttribs = {
                    EGL14.EGL_WIDTH, holder.width,
                    EGL14.EGL_HEIGHT, holder.height,
                    EGL14.EGL_NONE
            };
            holder.eglSurface =
                    EGL14.eglCreatePbufferSurface(mEGLDisplay, mConfigs, surfaceAttribs, 0);
            checkEglError("eglCreatePbufferSurface");
        }
        mPBufferPixels = ByteBuffer.allocateDirect(maxLength * PBUFFER_PIXEL_BYTES)
                .order(ByteOrder.nativeOrder());
    }

    private void releaseEGLContext() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            dumpGlTiming();
            if (mSurfaces != null) {
                for (EGLSurfaceHolder holder : mSurfaces) {
                    if (holder.eglSurface != null) {
                        EGL14.eglDestroySurface(mEGLDisplay, holder.eglSurface);
                    }
                }
            }
            if (mConversionSurfaces != null) {
                for (EGLSurfaceHolder holder : mConversionSurfaces) {
                    if (holder.eglSurface != null) {
                        EGL14.eglDestroySurface(mEGLDisplay, holder.eglSurface);
                    }
                }
            }
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        mConfigs = null;
        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        clearState();
    }

    private void makeCurrent(EGLSurface surface) {
        EGL14.eglMakeCurrent(mEGLDisplay, surface, surface, mEGLContext);
        checkEglError("makeCurrent");
    }

    private boolean swapBuffers(EGLSurface surface)
            throws LegacyExceptionUtils.BufferQueueAbandonedException {
        boolean result = EGL14.eglSwapBuffers(mEGLDisplay, surface);
        int error = EGL14.eglGetError();
        if (error == EGL14.EGL_BAD_SURFACE) {
            throw new LegacyExceptionUtils.BufferQueueAbandonedException();
        } else if (error != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException("swapBuffers: EGL error: 0x" +
                    Integer.toHexString(error));
        }
        return result;
    }

    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new IllegalStateException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    private void checkGlError(String msg) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(
                    msg + ": GLES20 error: 0x" + Integer.toHexString(error));
        }
    }

    private void checkGlDrawError(String msg)
            throws LegacyExceptionUtils.BufferQueueAbandonedException {
        int error;
        boolean surfaceAbandoned = false;
        boolean glError = false;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            if (error == GLES20.GL_OUT_OF_MEMORY) {
                surfaceAbandoned = true;
            } else {
                glError = true;
            }
        }
        if (glError) {
            throw new IllegalStateException(
                    msg + ": GLES20 error: 0x" + Integer.toHexString(error));
        }
        if (surfaceAbandoned) {
            throw new LegacyExceptionUtils.BufferQueueAbandonedException();
        }
    }

    /**
     * Save a measurement dump to disk, in
     * {@code /sdcard/CameraLegacy/durations_<time>_<width1>x<height1>_...txt}
     */
    private void dumpGlTiming() {
        if (mPerfMeasurer == null) return;

        File legacyStorageDir = new File(Environment.getExternalStorageDirectory(), "CameraLegacy");
        if (!legacyStorageDir.exists()){
            if (!legacyStorageDir.mkdirs()){
                Log.e(TAG, "Failed to create directory for data dump");
                return;
            }
        }

        StringBuilder path = new StringBuilder(legacyStorageDir.getPath());
        path.append(File.separator);
        path.append("durations_");

        Time now = new Time();
        now.setToNow();
        path.append(now.format2445());
        path.append("_S");
        for (EGLSurfaceHolder surface : mSurfaces) {
            path.append(String.format("_%d_%d", surface.width, surface.height));
        }
        path.append("_C");
        for (EGLSurfaceHolder surface : mConversionSurfaces) {
            path.append(String.format("_%d_%d", surface.width, surface.height));
        }
        path.append(".txt");
        mPerfMeasurer.dumpPerformanceData(path.toString());
    }

    private void setupGlTiming() {
        if (PerfMeasurement.isGlTimingSupported()) {
            Log.d(TAG, "Enabling GL performance measurement");
            mPerfMeasurer = new PerfMeasurement();
        } else {
            Log.d(TAG, "GL performance measurement not supported on this device");
            mPerfMeasurer = null;
        }
    }

    private void beginGlTiming() {
        if (mPerfMeasurer == null) return;
        mPerfMeasurer.startTimer();
    }

    private void addGlTimestamp(long timestamp) {
        if (mPerfMeasurer == null) return;
        mPerfMeasurer.addTimestamp(timestamp);
    }

    private void endGlTiming() {
        if (mPerfMeasurer == null) return;
        mPerfMeasurer.stopTimer();
    }

    /**
     * Return the surface texture to draw to - this is the texture use to when producing output
     * surface buffers.
     *
     * @return a {@link SurfaceTexture}.
     */
    public SurfaceTexture getSurfaceTexture() {
        return mSurfaceTexture;
    }

    /**
     * Set a collection of output {@link Surface}s that can be drawn to.
     *
     * @param surfaces a {@link Collection} of surfaces.
     */
    public void configureSurfaces(Collection<Pair<Surface, Size>> surfaces) {
        releaseEGLContext();

        if (surfaces == null || surfaces.size() == 0) {
            Log.w(TAG, "No output surfaces configured for GL drawing.");
            return;
        }

        for (Pair<Surface, Size> p : surfaces) {
            Surface s = p.first;
            Size surfaceSize = p.second;
            // If pixel conversions aren't handled by egl, use a pbuffer
            try {
                EGLSurfaceHolder holder = new EGLSurfaceHolder();
                holder.surface = s;
                holder.width = surfaceSize.getWidth();
                holder.height = surfaceSize.getHeight();
                if (LegacyCameraDevice.needsConversion(s)) {
                    mConversionSurfaces.add(holder);
                    // LegacyCameraDevice is the producer of surfaces if it's not handled by EGL,
                    // so LegacyCameraDevice needs to connect to the surfaces.
                    LegacyCameraDevice.connectSurface(s);
                } else {
                    mSurfaces.add(holder);
                }
            } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                Log.w(TAG, "Surface abandoned, skipping configuration... ", e);
            }
        }

        // Set up egl display
        configureEGLContext();

        // Set up regular egl surfaces if needed
        if (mSurfaces.size() > 0) {
            configureEGLOutputSurfaces(mSurfaces);
        }

        // Set up pbuffer surface if needed
        if (mConversionSurfaces.size() > 0) {
            configureEGLPbufferSurfaces(mConversionSurfaces);
        }
        makeCurrent((mSurfaces.size() > 0) ? mSurfaces.get(0).eglSurface :
                mConversionSurfaces.get(0).eglSurface);
        initializeGLState();
        mSurfaceTexture = new SurfaceTexture(getTextureId());

        // Set up performance tracking if enabled
        if (SystemProperties.getBoolean(LEGACY_PERF_PROPERTY, false)) {
            setupGlTiming();
        }
    }

    /**
     * Draw the current buffer in the {@link SurfaceTexture} returned from
     * {@link #getSurfaceTexture()} into the set of target {@link Surface}s
     * in the next request from the given {@link CaptureCollector}, or drop
     * the frame if none is available.
     *
     * <p>
     * Any {@link Surface}s targeted must be a subset of the {@link Surface}s
     * set in the last {@link #configureSurfaces(java.util.Collection)} call.
     * </p>
     *
     * @param targetCollector the surfaces to draw to.
     */
    public void drawIntoSurfaces(CaptureCollector targetCollector) {
        if ((mSurfaces == null || mSurfaces.size() == 0)
                && (mConversionSurfaces == null || mConversionSurfaces.size() == 0)) {
            return;
        }

        boolean doTiming = targetCollector.hasPendingPreviewCaptures();
        checkGlError("before updateTexImage");

        if (doTiming) {
            beginGlTiming();
        }

        mSurfaceTexture.updateTexImage();

        long timestamp = mSurfaceTexture.getTimestamp();

        Pair<RequestHolder, Long> captureHolder = targetCollector.previewCaptured(timestamp);

        // No preview request queued, drop frame.
        if (captureHolder == null) {
            if (DEBUG) {
                Log.d(TAG, "Dropping preview frame.");
            }
            if (doTiming) {
                endGlTiming();
            }
            return;
        }

        RequestHolder request = captureHolder.first;

        Collection<Surface> targetSurfaces = request.getHolderTargets();
        if (doTiming) {
            addGlTimestamp(timestamp);
        }

        List<Long> targetSurfaceIds = new ArrayList();
        try {
            targetSurfaceIds = LegacyCameraDevice.getSurfaceIds(targetSurfaces);
        } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
            Log.w(TAG, "Surface abandoned, dropping frame. ", e);
            request.setOutputAbandoned();
        }

        for (EGLSurfaceHolder holder : mSurfaces) {
            if (LegacyCameraDevice.containsSurfaceId(holder.surface, targetSurfaceIds)) {
                try{
                    LegacyCameraDevice.setSurfaceDimens(holder.surface, holder.width,
                            holder.height);
                    makeCurrent(holder.eglSurface);

                    LegacyCameraDevice.setNextTimestamp(holder.surface, captureHolder.second);
                    drawFrame(mSurfaceTexture, holder.width, holder.height,
                            (mFacing == CameraCharacteristics.LENS_FACING_FRONT) ?
                                    FLIP_TYPE_HORIZONTAL : FLIP_TYPE_NONE);
                    swapBuffers(holder.eglSurface);
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, dropping frame. ", e);
                    request.setOutputAbandoned();
                }
            }
        }
        for (EGLSurfaceHolder holder : mConversionSurfaces) {
            if (LegacyCameraDevice.containsSurfaceId(holder.surface, targetSurfaceIds)) {
                makeCurrent(holder.eglSurface);
                // glReadPixels reads from the bottom of the buffer, so add an extra vertical flip
                try {
                    drawFrame(mSurfaceTexture, holder.width, holder.height,
                            (mFacing == CameraCharacteristics.LENS_FACING_FRONT) ?
                                    FLIP_TYPE_BOTH : FLIP_TYPE_VERTICAL);
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    // Should never hit this.
                    throw new IllegalStateException("Surface abandoned, skipping drawFrame...", e);
                }
                mPBufferPixels.clear();
                GLES20.glReadPixels(/*x*/ 0, /*y*/ 0, holder.width, holder.height,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPBufferPixels);
                checkGlError("glReadPixels");

                try {
                    int format = LegacyCameraDevice.detectSurfaceType(holder.surface);
                    LegacyCameraDevice.setSurfaceDimens(holder.surface, holder.width,
                            holder.height);
                    LegacyCameraDevice.setNextTimestamp(holder.surface, captureHolder.second);
                    LegacyCameraDevice.produceFrame(holder.surface, mPBufferPixels.array(),
                            holder.width, holder.height, format);
                } catch (LegacyExceptionUtils.BufferQueueAbandonedException e) {
                    Log.w(TAG, "Surface abandoned, dropping frame. ", e);
                    request.setOutputAbandoned();
                }
            }
        }
        targetCollector.previewProduced();

        if (doTiming) {
            endGlTiming();
        }
    }

    /**
     * Clean up the current GL context.
     */
    public void cleanupEGLContext() {
        releaseEGLContext();
    }

    /**
     * Drop all current GL operations on the floor.
     */
    public void flush() {
        // TODO: implement flush
        Log.e(TAG, "Flush not yet implemented.");
    }
}

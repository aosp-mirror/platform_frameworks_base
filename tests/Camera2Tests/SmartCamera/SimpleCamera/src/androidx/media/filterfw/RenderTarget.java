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

package androidx.media.filterfw;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.media.MediaRecorder;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build.VERSION;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.util.HashMap;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public final class RenderTarget {

    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int EGL_OPENGL_ES2_BIT = 4;

    // Pre-HC devices do not necessarily support multiple display surfaces.
    private static boolean mSupportsMultipleDisplaySurfaces = (VERSION.SDK_INT >= 11);

    /** A Map that tracks which objects are wrapped by EGLSurfaces */
    private static HashMap<Object, EGLSurface> mSurfaceSources = new HashMap<Object, EGLSurface>();

    /** A Map for performing reference counting over shared objects across RenderTargets */
    private static HashMap<Object, Integer> mRefCounts = new HashMap<Object, Integer>();

    /** Stores the RenderTarget that is focused on the current thread. */
    private static ThreadLocal<RenderTarget> mCurrentTarget = new ThreadLocal<RenderTarget>();

    /** The source for the surface used in this target (if any) */
    private Object mSurfaceSource = null;

    /** The cached EGLConfig instance. */
    private static EGLConfig mEglConfig = null;

    /** The display for which the EGLConfig was chosen. We expect only one. */
    private static EGLDisplay mConfiguredDisplay;

    private EGL10 mEgl;
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private EGLSurface mSurface;
    private int mFbo;

    private boolean mOwnsContext;
    private boolean mOwnsSurface;

    private static HashMap<EGLContext, ImageShader> mIdShaders
        = new HashMap<EGLContext, ImageShader>();

    private static HashMap<EGLContext, EGLSurface> mDisplaySurfaces
        = new HashMap<EGLContext, EGLSurface>();

    private static int sRedSize = 8;
    private static int sGreenSize = 8;
    private static int sBlueSize = 8;
    private static int sAlphaSize = 8;
    private static int sDepthSize = 0;
    private static int sStencilSize = 0;

    public static RenderTarget newTarget(int width, int height) {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay eglDisplay = createDefaultDisplay(egl);
        EGLConfig eglConfig = chooseEglConfig(egl, eglDisplay);
        EGLContext eglContext = createContext(egl, eglDisplay, eglConfig);
        EGLSurface eglSurface = createSurface(egl, eglDisplay, width, height);
        RenderTarget result = new RenderTarget(eglDisplay, eglContext, eglSurface, 0, true, true);
        result.addReferenceTo(eglSurface);
        return result;
    }

    public static RenderTarget currentTarget() {
        // As RenderTargets are immutable, we can safely return the last focused instance on this
        // thread, as we know it cannot have changed, and therefore must be current.
        return mCurrentTarget.get();
    }

    public RenderTarget forTexture(TextureSource texture, int width, int height) {
        // NOTE: We do not need to lookup any previous bindings of this texture to an FBO, as
        // multiple FBOs to a single texture is valid.
        int fbo = GLToolbox.generateFbo();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLToolbox.checkGlError("glBindFramebuffer");
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                                      GLES20.GL_COLOR_ATTACHMENT0,
                                      texture.getTarget(),
                                      texture.getTextureId(),
                                      0);
        GLToolbox.checkGlError("glFramebufferTexture2D");
        return new RenderTarget(mDisplay, mContext, surface(), fbo, false, false);
    }

    public RenderTarget forSurfaceHolder(SurfaceHolder surfaceHolder) {
        EGLConfig eglConfig = chooseEglConfig(mEgl, mDisplay);
        EGLSurface eglSurf = null;
        synchronized (mSurfaceSources) {
            eglSurf = mSurfaceSources.get(surfaceHolder);
            if (eglSurf == null) {
                eglSurf = mEgl.eglCreateWindowSurface(mDisplay, eglConfig, surfaceHolder, null);
                mSurfaceSources.put(surfaceHolder, eglSurf);
            }
        }
        checkEglError(mEgl, "eglCreateWindowSurface");
        checkSurface(mEgl, eglSurf);
        RenderTarget result = new RenderTarget(mDisplay, mContext, eglSurf, 0, false, true);
        result.addReferenceTo(eglSurf);
        result.setSurfaceSource(surfaceHolder);
        return result;
    }

    @TargetApi(11)
    public RenderTarget forSurfaceTexture(SurfaceTexture surfaceTexture) {
        EGLConfig eglConfig = chooseEglConfig(mEgl, mDisplay);
        EGLSurface eglSurf = null;
        synchronized (mSurfaceSources) {
            eglSurf = mSurfaceSources.get(surfaceTexture);
            if (eglSurf == null) {
                eglSurf = mEgl.eglCreateWindowSurface(mDisplay, eglConfig, surfaceTexture, null);
                mSurfaceSources.put(surfaceTexture, eglSurf);
            }
        }
        checkEglError(mEgl, "eglCreateWindowSurface");
        checkSurface(mEgl, eglSurf);
        RenderTarget result = new RenderTarget(mDisplay, mContext, eglSurf, 0, false, true);
        result.setSurfaceSource(surfaceTexture);
        result.addReferenceTo(eglSurf);
        return result;
    }

    @TargetApi(11)
    public RenderTarget forSurface(Surface surface) {
        EGLConfig eglConfig = chooseEglConfig(mEgl, mDisplay);
        EGLSurface eglSurf = null;
        synchronized (mSurfaceSources) {
            eglSurf = mSurfaceSources.get(surface);
            if (eglSurf == null) {
                eglSurf = mEgl.eglCreateWindowSurface(mDisplay, eglConfig, surface, null);
                mSurfaceSources.put(surface, eglSurf);
            }
        }
        checkEglError(mEgl, "eglCreateWindowSurface");
        checkSurface(mEgl, eglSurf);
        RenderTarget result = new RenderTarget(mDisplay, mContext, eglSurf, 0, false, true);
        result.setSurfaceSource(surface);
        result.addReferenceTo(eglSurf);
        return result;
    }

    public static RenderTarget forMediaRecorder(MediaRecorder mediaRecorder) {
        throw new RuntimeException("Not yet implemented MediaRecorder -> RenderTarget!");
    }

    public static void setEGLConfigChooser(int redSize, int greenSize, int blueSize, int alphaSize,
            int depthSize, int stencilSize) {
        sRedSize = redSize;
        sGreenSize = greenSize;
        sBlueSize = blueSize;
        sAlphaSize = alphaSize;
        sDepthSize = depthSize;
        sStencilSize = stencilSize;
    }

    public void registerAsDisplaySurface() {
        if (!mSupportsMultipleDisplaySurfaces) {
            // Note that while this does in effect change RenderTarget instances (by modifying
            // their returned EGLSurface), breaking the immutability requirement, it does not modify
            // the current target. This is important so that the instance returned in
            // currentTarget() remains accurate.
            EGLSurface currentSurface = mDisplaySurfaces.get(mContext);
            if (currentSurface != null && !currentSurface.equals(mSurface)) {
                throw new RuntimeException("This device supports only a single display surface!");
            } else {
                mDisplaySurfaces.put(mContext, mSurface);
            }
        }
    }

    public void unregisterAsDisplaySurface() {
        if (!mSupportsMultipleDisplaySurfaces) {
            mDisplaySurfaces.put(mContext, null);
        }
    }

    public void focus() {
        RenderTarget current = mCurrentTarget.get();
        // We assume RenderTargets are immutable, so that we do not need to focus if the current
        // RenderTarget has not changed.
        if (current != this) {
            mEgl.eglMakeCurrent(mDisplay, surface(), surface(), mContext);
            mCurrentTarget.set(this);
        }
        if (getCurrentFbo() != mFbo) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbo);
            GLToolbox.checkGlError("glBindFramebuffer");
        }
    }

    public static void focusNone() {
        EGL10 egl = (EGL10) EGLContext.getEGL();
        egl.eglMakeCurrent(egl.eglGetCurrentDisplay(),
                           EGL10.EGL_NO_SURFACE,
                           EGL10.EGL_NO_SURFACE,
                           EGL10.EGL_NO_CONTEXT);
        mCurrentTarget.set(null);
        checkEglError(egl, "eglMakeCurrent");
    }

    public void swapBuffers() {
        mEgl.eglSwapBuffers(mDisplay, surface());
    }

    public EGLContext getContext() {
        return mContext;
    }

    public static EGLContext currentContext() {
        RenderTarget current = RenderTarget.currentTarget();
        return current != null ? current.getContext() : EGL10.EGL_NO_CONTEXT;
    }

    public void release() {
        if (mOwnsContext) {
            mEgl.eglDestroyContext(mDisplay, mContext);
            mContext = EGL10.EGL_NO_CONTEXT;
        }
        if (mOwnsSurface) {
            synchronized (mSurfaceSources) {
                if (removeReferenceTo(mSurface)) {
                    mEgl.eglDestroySurface(mDisplay, mSurface);
                    mSurface = EGL10.EGL_NO_SURFACE;
                    mSurfaceSources.remove(mSurfaceSource);
                }
            }
        }
        if (mFbo != 0) {
           GLToolbox.deleteFbo(mFbo);
       }
    }

    public void readPixelData(ByteBuffer pixels, int width, int height) {
        GLToolbox.readTarget(this, pixels, width, height);
    }

    public ByteBuffer getPixelData(int width, int height) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GLToolbox.readTarget(this, pixels, width, height);
        return pixels;
    }

    /**
     * Returns an identity shader for this context.
     * You must not modify this shader. Use {@link ImageShader#createIdentity()} if you need to
     * modify an identity shader.
     */
    public ImageShader getIdentityShader() {
        ImageShader idShader = mIdShaders.get(mContext);
        if (idShader == null) {
            idShader = ImageShader.createIdentity();
            mIdShaders.put(mContext, idShader);
        }
        return idShader;
    }

    @Override
    public String toString() {
        return "RenderTarget(" + mDisplay + ", " + mContext + ", " + mSurface + ", " + mFbo + ")";
    }

    private void setSurfaceSource(Object source) {
        mSurfaceSource = source;
    }

    private void addReferenceTo(Object object) {
        Integer refCount = mRefCounts.get(object);
        if (refCount != null) {
            mRefCounts.put(object, refCount + 1);
        } else {
            mRefCounts.put(object, 1);
        }
    }

    private boolean removeReferenceTo(Object object) {
        Integer refCount = mRefCounts.get(object);
        if (refCount != null && refCount > 0) {
            --refCount;
            mRefCounts.put(object, refCount);
            return refCount == 0;
        } else {
            Log.e("RenderTarget", "Removing reference of already released: " + object + "!");
            return false;
        }
    }

    private static EGLConfig chooseEglConfig(EGL10 egl, EGLDisplay display) {
        if (mEglConfig == null || !display.equals(mConfiguredDisplay)) {
            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = getDesiredConfig();
            if (!egl.eglChooseConfig(display, configSpec, configs, 1, configsCount)) {
                throw new IllegalArgumentException("EGL Error: eglChooseConfig failed " +
                        getEGLErrorString(egl, egl.eglGetError()));
            } else if (configsCount[0] > 0) {
                mEglConfig = configs[0];
                mConfiguredDisplay = display;
            }
        }
        return mEglConfig;
    }

    private static int[] getDesiredConfig() {
        return new int[] {
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, sRedSize,
                EGL10.EGL_GREEN_SIZE, sGreenSize,
                EGL10.EGL_BLUE_SIZE, sBlueSize,
                EGL10.EGL_ALPHA_SIZE, sAlphaSize,
                EGL10.EGL_DEPTH_SIZE, sDepthSize,
                EGL10.EGL_STENCIL_SIZE, sStencilSize,
                EGL10.EGL_NONE
        };
    }

    private RenderTarget(EGLDisplay display, EGLContext context, EGLSurface surface, int fbo,
                         boolean ownsContext, boolean ownsSurface) {
        mEgl = (EGL10) EGLContext.getEGL();
        mDisplay = display;
        mContext = context;
        mSurface = surface;
        mFbo = fbo;
        mOwnsContext = ownsContext;
        mOwnsSurface = ownsSurface;
    }

    private EGLSurface surface() {
        if (mSupportsMultipleDisplaySurfaces) {
            return mSurface;
        } else {
            EGLSurface displaySurface = mDisplaySurfaces.get(mContext);
            return displaySurface != null ? displaySurface : mSurface;
        }
    }

    private static void initEgl(EGL10 egl, EGLDisplay display) {
        int[] version = new int[2];
        if (!egl.eglInitialize(display, version)) {
            throw new RuntimeException("EGL Error: eglInitialize failed " +
                    getEGLErrorString(egl, egl.eglGetError()));
        }
    }

    private static EGLDisplay createDefaultDisplay(EGL10 egl) {
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        checkDisplay(egl, display);
        initEgl(egl, display);
        return display;
    }

    private static EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
        int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
        EGLContext ctxt = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attrib_list);
        checkContext(egl, ctxt);
        return ctxt;
    }

    private static EGLSurface createSurface(EGL10 egl, EGLDisplay display, int width, int height) {
        EGLConfig eglConfig = chooseEglConfig(egl, display);
        int[] attribs = { EGL10.EGL_WIDTH, width, EGL10.EGL_HEIGHT, height, EGL10.EGL_NONE };
        return egl.eglCreatePbufferSurface(display, eglConfig, attribs);
    }

    private static int getCurrentFbo() {
        int[] result = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, result, 0);
        return result[0];
    }

    private static void checkDisplay(EGL10 egl, EGLDisplay display) {
        if (display == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL Error: Bad display: "
                    + getEGLErrorString(egl, egl.eglGetError()));
        }
    }

    private static void checkContext(EGL10 egl, EGLContext context) {
        if (context == EGL10.EGL_NO_CONTEXT) {
            throw new RuntimeException("EGL Error: Bad context: "
                    + getEGLErrorString(egl, egl.eglGetError()));
        }
    }

    private static void checkSurface(EGL10 egl, EGLSurface surface) {
        if (surface == EGL10.EGL_NO_SURFACE) {
            throw new RuntimeException("EGL Error: Bad surface: "
                    + getEGLErrorString(egl, egl.eglGetError()));
        }
    }

    private static void checkEglError(EGL10 egl, String command) {
        int error = egl.eglGetError();
        if (error != EGL10.EGL_SUCCESS) {
            throw new RuntimeException("Error executing " + command + "! EGL error = 0x"
                + Integer.toHexString(error));
        }
    }

    private static String getEGLErrorString(EGL10 egl, int eglError) {
        if (VERSION.SDK_INT >= 14) {
            return getEGLErrorStringICS(egl, eglError);
        } else {
            return "EGL Error 0x" + Integer.toHexString(eglError);
        }
    }

    @TargetApi(14)
    private static String getEGLErrorStringICS(EGL10 egl, int eglError) {
        return GLUtils.getEGLErrorString(egl.eglGetError());
    }
}


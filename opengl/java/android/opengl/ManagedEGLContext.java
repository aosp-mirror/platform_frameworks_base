/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.opengl;

import static javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY;
import static javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY;

import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import android.os.Looper;
import android.util.Log;

import com.google.android.gles_jni.EGLImpl;

/**
 * The per-process memory overhead of hardware accelerated graphics can
 * be quite large on some devices.  For small memory devices, being able to
 * terminate all EGL contexts so that this graphics driver memory can be
 * reclaimed can significant improve the overall behavior of the device.  This
 * class helps app developers participate in releasing their EGL context
 * when appropriate and possible.
 * 
 * <p>To use, simple instantiate this class with the EGLContext you create.
 * When you have done this, if the device is getting low on memory and all
 * of the currently created EGL contexts in the process are being managed
 * through this class, then they will all be asked to terminate through the
 * call to {@link #onTerminate}.
 */
public abstract class ManagedEGLContext {
    static final String TAG = "ManagedEGLContext";

    static final ArrayList<ManagedEGLContext> sActive = new ArrayList<ManagedEGLContext>();

    final EGLContext mContext;

    /**
     * Instantiate to manage the given EGLContext.
     */
    public ManagedEGLContext(EGLContext context) {
        mContext = context;
        synchronized (sActive) {
            sActive.add(this);
        }
    }

    /**
     * Retrieve the EGLContext being managed by the class.
     */
    public EGLContext getContext() {
        return mContext;
    }

    /**
     * Force-terminate the ManagedEGLContext.  This will cause
     * {@link #onTerminate(EGLContext)} to be called.  You <em>must</em>
     * call this when destroying the EGLContext, so that the framework
     * knows to stop managing it.
     */
    public void terminate() {
        execTerminate();
    }

    void execTerminate() {
        onTerminate(mContext);
    }

    /**
     * Override this method to destroy the EGLContext when appropriate.
     * <em>Note that this method is always called on the main thread
     * of the process.</em>  If your EGLContext was created on a different
     * thread, you will need to implement this method to hand off the work
     * of destroying the context to that thread.
     */
    public abstract void onTerminate(EGLContext context);

    /** @hide */
    public static boolean doTerminate() {
        ArrayList<ManagedEGLContext> active;

        if (Looper.getMainLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Called on wrong thread");
        }

        synchronized (sActive) {
            // If there are no active managed contexts, we will not even
            // try to terminate.
            if (sActive.size() <= 0) {
                return false;
            }

            // Need to check how many EGL contexts are actually running,
            // to compare with how many we are managing.
            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay display = egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);

            if (display == EGL_NO_DISPLAY) {
                Log.w(TAG, "doTerminate failed: no display");
                return false;
            }

            if (EGLImpl.getInitCount(display) != sActive.size()) {
                Log.w(TAG, "doTerminate failed: EGL count is " + EGLImpl.getInitCount(display)
                        + " but managed count is " + sActive.size());
                return false;
            }

            active = new ArrayList<ManagedEGLContext>(sActive);
            sActive.clear();
        }

        for (int i = 0; i < active.size(); i++) {
            active.get(i).execTerminate();
        }

        return true;
    }
}

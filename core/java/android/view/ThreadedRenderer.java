/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Hardware renderer that proxies the rendering to a render thread. Most calls
 * are synchronous, however a few such as draw() are posted async. The display list
 * is shared between the two threads and is guarded by a top level lock.
 *
 * The UI thread can block on the RenderThread, but RenderThread must never
 * block on the UI thread.
 *
 * Note that although currently the EGL context & surfaces are created & managed
 * by the render thread, the goal is to move that into a shared structure that can
 * be managed by both threads. EGLSurface creation & deletion should ideally be
 * done on the UI thread and not the RenderThread to avoid stalling the
 * RenderThread with surface buffer allocation.
 *
 * @hide
 */
public class ThreadedRenderer extends HardwareRenderer {
    private static final String LOGTAG = "ThreadedRenderer";

    @SuppressWarnings("serial")
    static HashMap<String, Method> sMethodLut = new HashMap<String, Method>() {{
        Method[] methods = RemoteGLRenderer.class.getDeclaredMethods();
        for (Method m : methods) {
            m.setAccessible(true);
            put(m.getName() + ":" + m.getParameterTypes().length, m);
        }
    }};
    static boolean sNeedsInit = true;

    private RemoteGLRenderer mRemoteRenderer;
    private int mWidth, mHeight;
    private RTJob mPreviousDraw;

    ThreadedRenderer(boolean translucent) {
        mRemoteRenderer = new RemoteGLRenderer(this, translucent);
        setEnabled(true);
        if (sNeedsInit) {
            sNeedsInit = false;
            postToRenderThread(new Runnable() {
                @Override
                public void run() {
                    // Hack to allow GLRenderer to create a handler to post the EGL
                    // destruction to, although it'll never run
                    Looper.prepare();
                }
            });
        }
    }

    @Override
    void destroy(boolean full) {
        run("destroy", full);
    }

    @Override
    boolean initialize(Surface surface) throws OutOfResourcesException {
        return (Boolean) run("initialize", surface);
    }

    @Override
    void updateSurface(Surface surface) throws OutOfResourcesException {
        post("updateSurface", surface);
    }

    @Override
    void destroyLayers(View view) {
        throw new NoSuchMethodError();
    }

    @Override
    void destroyHardwareResources(View view) {
        run("destroyHardwareResources", view);
    }

    @Override
    void invalidate(Surface surface) {
        post("invalidate", surface);
    }

    @Override
    boolean validate() {
        // TODO Remove users of this API
        return false;
    }

    @Override
    boolean safelyRun(Runnable action) {
        return (Boolean) run("safelyRun", action);
    }

    @Override
    void setup(int width, int height) {
        mWidth = width;
        mHeight = height;
        post("setup", width, height);
    }

    @Override
    int getWidth() {
        return mWidth;
    }

    @Override
    int getHeight() {
        return mHeight;
    }

    @Override
    void dumpGfxInfo(PrintWriter pw) {
        // TODO Auto-generated method stub
    }

    @Override
    long getFrameCount() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    boolean loadSystemProperties() {
        return (Boolean) run("loadSystemProperties");
    }

    @Override
    void pushLayerUpdate(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void cancelLayerUpdate(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void flushLayerUpdates() {
        throw new NoSuchMethodError();
    }

    /**
     * TODO: Remove
     * Temporary hack to allow RenderThreadTest prototype app to trigger
     * replaying a DisplayList after modifying the displaylist properties
     *
     *  @hide */
    public void repeatLastDraw() {
        if (mPreviousDraw == null) {
            throw new IllegalStateException("There isn't a previous draw");
        }
        synchronized (mPreviousDraw) {
            mPreviousDraw.completed = false;
        }
        mPreviousDraw.args[3] = null;
        postToRenderThread(mPreviousDraw);
    }

    @Override
    void draw(View view, AttachInfo attachInfo, HardwareDrawCallbacks callbacks, Rect dirty) {
        requireCompletion(mPreviousDraw);

        attachInfo.mIgnoreDirtyState = true;
        attachInfo.mDrawingTime = SystemClock.uptimeMillis();
        view.mPrivateFlags |= View.PFLAG_DRAWN;

        view.mRecreateDisplayList = (view.mPrivateFlags & View.PFLAG_INVALIDATED)
                == View.PFLAG_INVALIDATED;
        view.mPrivateFlags &= ~View.PFLAG_INVALIDATED;

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "getDisplayList");
        DisplayList displayList = view.getDisplayList();
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);

        view.mRecreateDisplayList = false;

        mPreviousDraw = post("drawDisplayList", displayList, attachInfo,
                callbacks, dirty);
    }

    @Override
    HardwareLayer createHardwareLayer(boolean isOpaque) {
        throw new NoSuchMethodError();
    }

    @Override
    HardwareLayer createHardwareLayer(int width, int height, boolean isOpaque) {
        throw new NoSuchMethodError();
    }

    @Override
    SurfaceTexture createSurfaceTexture(HardwareLayer layer) {
        throw new NoSuchMethodError();
    }

    @Override
    void setSurfaceTexture(HardwareLayer layer, SurfaceTexture surfaceTexture) {
        throw new NoSuchMethodError();
    }

    @Override
    void detachFunctor(long functor) {
        run("detachFunctor", functor);
    }

    @Override
    boolean attachFunctor(AttachInfo attachInfo, long functor) {
        return (Boolean) run("attachFunctor", attachInfo, functor);
    }

    @Override
    void setName(String name) {
        post("setName", name);
    }

    private static void requireCompletion(RTJob job) {
        if (job != null) {
            synchronized (job) {
                if (!job.completed) {
                    try {
                        job.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private RTJob post(String method, Object... args) {
        RTJob job = new RTJob();
        job.method = sMethodLut.get(method + ":" + args.length);
        job.args = args;
        job.target = mRemoteRenderer;
        if (job.method == null) {
            throw new NullPointerException("Couldn't find method: " + method);
        }
        postToRenderThread(job);
        return job;
    }

    private Object run(String method, Object... args) {
        RTJob job = new RTJob();
        job.method = sMethodLut.get(method + ":" + args.length);
        job.args = args;
        job.target = mRemoteRenderer;
        if (job.method == null) {
            throw new NullPointerException("Couldn't find method: " + method);
        }
        synchronized (job) {
            postToRenderThread(job);
            try {
                job.wait();
                return job.ret;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class RTJob implements Runnable {
        Method method;
        Object[] args;
        Object target;
        Object ret;
        boolean completed = false;

        @Override
        public void run() {
            try {
                ret = method.invoke(target, args);
                synchronized (this) {
                    completed = true;
                    notify();
                }
            } catch (Exception e) {
                Log.e(LOGTAG, "Failed to invoke: " + method.getName(), e);
            }
        }
    }

    /** @hide */
    public static native void postToRenderThread(Runnable runnable);
}

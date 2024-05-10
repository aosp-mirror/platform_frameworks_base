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

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.util.HashSet;
import java.util.Set;

/**
 * The MffContext holds the state and resources of a Mobile Filter Framework processing instance.
 * Though it is possible to create multiple MffContext instances, typical applications will rely on
 * a single MffContext to perform all processing within the Mobile Filter Framework.
 *
 * The MffContext class declares two methods {@link #onPause()} and {@link #onResume()}, that are
 * typically called when the application activity is paused and resumed. This will take care of
 * halting any processing in the context, and releasing resources while the activity is paused.
 */
public class MffContext {

    /**
     * Class to hold configuration information for MffContexts.
     */
    public static class Config {
        /**
         * Set to true, if this context will make use of the camera.
         * If your application does not require the camera, the context does not guarantee that
         * a camera is available for streaming. That is, you may only use a CameraStreamer if
         * the context's {@link #isCameraStreamingSupported()} returns true.
         */
        public boolean requireCamera = true;

        /**
         * Set to true, if this context requires OpenGL.
         * If your application does not require OpenGL, the context does not guarantee that OpenGL
         * is available. That is, you may only use OpenGL (within filters running in this context)
         * if the context's {@link #isOpenGLSupported()} method returns true.
         */
        public boolean requireOpenGL = true;

        /**
         * On older Android versions the Camera may need a SurfaceView to render into in order to
         * function. You may specify a placeholder SurfaceView here if you do not want the context to
         * create its own view. Note, that your view may or may not be used. You cannot rely on
         * your placeholder view to be used by the Camera. If you pass null, no placeholder view will be used.
         * In this case your application may not run correctly on older devices if you use the
         * camera. This flag has no effect if you do not require the camera.
         */
        public SurfaceView dummySurface = null;

        /** Force MFF to not use OpenGL in its processing. */
        public boolean forceNoGL = false;
    }

    static private class State {
        public static final int STATE_RUNNING = 1;
        public static final int STATE_PAUSED = 2;
        public static final int STATE_DESTROYED = 3;

        public int current = STATE_RUNNING;
    }

    /** The application context. */
    private Context mApplicationContext = null;

    /** The set of filter graphs within this context */
    private Set<FilterGraph> mGraphs = new HashSet<FilterGraph>();

    /** The set of graph runners within this context */
    private Set<GraphRunner> mRunners = new HashSet<GraphRunner>();

    /** True, if the context preserves frames when paused. */
    private boolean mPreserveFramesOnPause = false;

    /** The shared CameraStreamer that streams camera frames to CameraSource filters. */
    private CameraStreamer mCameraStreamer = null;

    /** The current context state. */
    private State mState = new State();

    /** A placeholder SurfaceView that is required for Camera operation on older devices. */
    private SurfaceView mDummySurfaceView = null;

    /** Handler to execute code in the context's thread, such as issuing callbacks. */
    private Handler mHandler = null;

    /** Flag whether OpenGL ES 2 is supported in this context. */
    private boolean mGLSupport;

    /** Flag whether camera streaming is supported in this context. */
    private boolean mCameraStreamingSupport;

    /**
     * Creates a new MffContext with the default configuration.
     *
     * An MffContext must be attached to a Context object of an application. You may create
     * multiple MffContexts, however data between them cannot be shared. The context must be
     * created in a thread with a Looper (such as the main/UI thread).
     *
     * On older versions of Android, the MffContext may create a visible placeholder view for the
     * camera to render into. This is a 1x1 SurfaceView that is placed into the top-left corner.
     *
     * @param context The application context to attach the MffContext to.
     */
    public MffContext(Context context) {
        init(context, new Config());
    }

    /**
     * Creates a new MffContext with the specified configuration.
     *
     * An MffContext must be attached to a Context object of an application. You may create
     * multiple MffContexts, however data between them cannot be shared. The context must be
     * created in a thread with a Looper (such as the main/UI thread).
     *
     * On older versions of Android, the MffContext may create a visible placeholder view for the
     * camera to render into. This is a 1x1 SurfaceView that is placed into the top-left corner.
     * You may alternatively specify your own SurfaceView in the configuration.
     *
     * @param context The application context to attach the MffContext to.
     * @param config The configuration to use.
     *
     * @throws RuntimeException If no context for the requested configuration could be created.
     */
    public MffContext(Context context, Config config) {
        init(context, config);
    }

    /**
     * Put all processing in the context on hold.
     * This is typically called from your application's <code>onPause()</code> method, and will
     * stop all running graphs (closing their filters). If the context does not preserve frames on
     * pause (see {@link #setPreserveFramesOnPause(boolean)}) all frames attached to this context
     * are released.
     */
    public void onPause() {
        synchronized (mState) {
            if (mState.current == State.STATE_RUNNING) {
                if (mCameraStreamer != null) {
                    mCameraStreamer.halt();
                }
                stopRunners(true);
                mState.current = State.STATE_PAUSED;
            }
        }
    }

    /**
     * Resumes the processing in this context.
     * This is typically called from the application's <code>onResume()</code> method, and will
     * resume processing any of the previously stopped filter graphs.
     */
    public void onResume() {
        synchronized (mState) {
            if (mState.current == State.STATE_PAUSED) {
                resumeRunners();
                resumeCamera();
                mState.current = State.STATE_RUNNING;
            }
        }
    }

    /**
     * Release all resources associated with this context.
     * This will also stop any running graphs.
     */
    public void release() {
        synchronized (mState) {
            if (mState.current != State.STATE_DESTROYED) {
                if (mCameraStreamer != null) {
                    mCameraStreamer.stop();
                    mCameraStreamer.tearDown();
                }

                stopRunners(false);
                waitUntilStopped();
                tearDown();
                mState.current = State.STATE_DESTROYED;
            }
        }
    }

    /**
     * Set whether frames are preserved when the context is paused.
     * When passing false, all Frames associated with this context are released. The default
     * value is true.
     *
     * @param preserve true, to preserve frames when the context is paused.
     *
     * @see #getPreserveFramesOnPause()
     */
    public void setPreserveFramesOnPause(boolean preserve) {
        mPreserveFramesOnPause = preserve;
    }

    /**
     * Returns whether frames are preserved when the context is paused.
     *
     * @return true, if frames are preserved when the context is paused.
     *
     * @see #setPreserveFramesOnPause(boolean)
     */
    public boolean getPreserveFramesOnPause() {
        return mPreserveFramesOnPause;
    }

    /**
     * Returns the application context that the MffContext is attached to.
     *
     * @return The application context for this context.
     */
    public Context getApplicationContext() {
        return mApplicationContext;
    }

    /**
     * Returns the context's shared CameraStreamer.
     * Use the CameraStreamer to control the Camera. Frames from the Camera are typically streamed
     * to CameraSource filters.
     *
     * @return The context's CameraStreamer instance.
     */
    public CameraStreamer getCameraStreamer() {
        if (mCameraStreamer == null) {
            mCameraStreamer = new CameraStreamer(this);
        }
        return mCameraStreamer;
    }

    /**
     * Set the default EGL config chooser.
     *
     * When an EGL context is required by the MFF, the channel sizes specified here are used. The
     * default sizes are 8 bits per R,G,B,A channel and 0 bits for depth and stencil channels.
     *
     * @param redSize The size of the red channel in bits.
     * @param greenSize The size of the green channel in bits.
     * @param blueSize The size of the blue channel in bits.
     * @param alphaSize The size of the alpha channel in bits.
     * @param depthSize The size of the depth channel in bits.
     * @param stencilSize The size of the stencil channel in bits.
     */
    public static void setEGLConfigChooser(int redSize,
                                           int greenSize,
                                           int blueSize,
                                           int alphaSize,
                                           int depthSize,
                                           int stencilSize) {
        RenderTarget.setEGLConfigChooser(redSize,
                                         greenSize,
                                         blueSize,
                                         alphaSize,
                                         depthSize,
                                         stencilSize);
    }

    /**
     * Returns true, if this context supports using OpenGL.
     * @return true, if this context supports using OpenGL.
     */
    public final boolean isOpenGLSupported() {
        return mGLSupport;
    }

    /**
     * Returns true, if this context supports camera streaming.
     * @return true, if this context supports camera streaming.
     */
    public final boolean isCameraStreamingSupported() {
        return mCameraStreamingSupport;
    }

    final void assertOpenGLSupported() {
        if (!isOpenGLSupported()) {
            throw new RuntimeException("Attempting to use OpenGL ES 2 in a context that does not "
                    + "support it!");
        }
    }

    void addGraph(FilterGraph graph) {
        synchronized (mGraphs) {
            mGraphs.add(graph);
        }
    }

    void addRunner(GraphRunner runner) {
        synchronized (mRunners) {
            mRunners.add(runner);
        }
    }

    SurfaceView getDummySurfaceView() {
        return mDummySurfaceView;
    }

    void postRunnable(Runnable runnable) {
        mHandler.post(runnable);
    }

    private void init(Context context, Config config) {
        determineGLSupport(context, config);
        determineCameraSupport(config);
        createHandler();
        mApplicationContext = context.getApplicationContext();
        fetchDummySurfaceView(context, config);
    }

    private void fetchDummySurfaceView(Context context, Config config) {
        if (config.requireCamera && CameraStreamer.requireDummySurfaceView()) {
            mDummySurfaceView = config.dummySurface != null
                    ? config.dummySurface
                    : createDummySurfaceView(context);
        }
    }

    private void determineGLSupport(Context context, Config config) {
        if (config.forceNoGL) {
            mGLSupport = false;
        } else {
            mGLSupport = getPlatformSupportsGLES2(context);
            if (config.requireOpenGL && !mGLSupport) {
                throw new RuntimeException("Cannot create context that requires GL support on "
                        + "this platform!");
            }
        }
    }

    private void determineCameraSupport(Config config) {
        mCameraStreamingSupport = (CameraStreamer.getNumberOfCameras() > 0);
        if (config.requireCamera && !mCameraStreamingSupport) {
            throw new RuntimeException("Cannot create context that requires a camera on "
                    + "this platform!");
        }
    }

    private static boolean getPlatformSupportsGLES2(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo configurationInfo = am.getDeviceConfigurationInfo();
        return configurationInfo.reqGlEsVersion >= 0x20000;
    }

    private void createHandler() {
        if (Looper.myLooper() == null) {
            throw new RuntimeException("MffContext must be created in a thread with a Looper!");
        }
        mHandler = new Handler();
    }

    private void stopRunners(boolean haltOnly) {
        synchronized (mRunners) {
            // Halt all runners (does nothing if not running)
            for (GraphRunner runner : mRunners) {
                if (haltOnly) {
                    runner.halt();
                } else {
                    runner.stop();
                }
            }
            // Flush all graphs if requested (this is queued up after the call to halt)
            if (!mPreserveFramesOnPause) {
                for (GraphRunner runner : mRunners) {
                    runner.flushFrames();
                }
            }
        }
    }

    private void resumeRunners() {
        synchronized (mRunners) {
            for (GraphRunner runner : mRunners) {
                runner.restart();
            }
        }
    }

    private void resumeCamera() {
        // Restart only affects previously halted cameras that were running.
        if (mCameraStreamer != null) {
            mCameraStreamer.restart();
        }
    }

    private void waitUntilStopped() {
        for (GraphRunner runner : mRunners) {
            runner.waitUntilStop();
        }
    }

    private void tearDown() {
        // Tear down graphs
        for (FilterGraph graph : mGraphs) {
            graph.tearDown();
        }

        // Tear down runners
        for (GraphRunner runner : mRunners) {
            runner.tearDown();
        }
    }

    @SuppressWarnings("deprecation")
    private SurfaceView createDummySurfaceView(Context context) {
        // This is only called on Gingerbread devices, so deprecation warning is unnecessary.
        SurfaceView dummySurfaceView = new SurfaceView(context);
        dummySurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        // If we have an activity for this context we'll add the SurfaceView to it (as a 1x1 view
        // in the top-left corner). If not, we warn the user that they may need to add one manually.
        Activity activity = findActivityForContext(context);
        if (activity != null) {
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(1, 1);
            activity.addContentView(dummySurfaceView, params);
        } else {
            Log.w("MffContext", "Could not find activity for dummy surface! Consider specifying "
                    + "your own SurfaceView!");
        }
        return dummySurfaceView;
    }

    private Activity findActivityForContext(Context context) {
        return (context instanceof Activity) ? (Activity) context : null;
    }

}

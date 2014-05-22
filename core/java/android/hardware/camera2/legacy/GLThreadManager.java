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

import android.graphics.SurfaceTexture;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.util.Collection;

/**
 * GLThreadManager handles the thread used for rendering into the configured output surfaces.
 */
public class GLThreadManager {
    private final String TAG;
    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);

    private static final int MSG_NEW_CONFIGURATION = 1;
    private static final int MSG_NEW_FRAME = 2;
    private static final int MSG_CLEANUP = 3;
    private static final int MSG_DROP_FRAMES = 4;
    private static final int MSG_ALLOW_FRAMES = 5;

    private final SurfaceTextureRenderer mTextureRenderer;

    private final RequestHandlerThread mGLHandlerThread;

    private final RequestThreadManager.FpsCounter mPrevCounter =
            new RequestThreadManager.FpsCounter("GL Preview Producer");

    /**
     * Container object for Configure messages.
     */
    private static class ConfigureHolder {
        public final ConditionVariable condition;
        public final Collection<Surface> surfaces;

        public ConfigureHolder(ConditionVariable condition, Collection<Surface> surfaces) {
            this.condition = condition;
            this.surfaces = surfaces;
        }
    }

    private final Handler.Callback mGLHandlerCb = new Handler.Callback() {
        private boolean mCleanup = false;
        private boolean mConfigured = false;
        private boolean mDroppingFrames = false;

        @SuppressWarnings("unchecked")
        @Override
        public boolean handleMessage(Message msg) {
            if (mCleanup) {
                return true;
            }
            switch (msg.what) {
                case MSG_NEW_CONFIGURATION:
                    ConfigureHolder configure = (ConfigureHolder) msg.obj;
                    mTextureRenderer.cleanupEGLContext();
                    mTextureRenderer.configureSurfaces(configure.surfaces);
                    configure.condition.open();
                    mConfigured = true;
                    break;
                case MSG_NEW_FRAME:
                    if (mDroppingFrames) {
                        Log.w(TAG, "Ignoring frame.");
                        break;
                    }
                    if (DEBUG) {
                        mPrevCounter.countAndLog();
                    }
                    if (!mConfigured) {
                        Log.e(TAG, "Dropping frame, EGL context not configured!");
                    }
                    mTextureRenderer.drawIntoSurfaces((Collection<Surface>) msg.obj);
                    break;
                case MSG_CLEANUP:
                    mTextureRenderer.cleanupEGLContext();
                    mCleanup = true;
                    mConfigured = false;
                    break;
                case MSG_DROP_FRAMES:
                    mDroppingFrames = true;
                    break;
                case MSG_ALLOW_FRAMES:
                    mDroppingFrames = false;
                default:
                    Log.e(TAG, "Unhandled message " + msg.what + " on GLThread.");
                    break;
            }
            return true;
        }
    };

    /**
     * Create a new GL thread and renderer.
     *
     * @param cameraId the camera id for this thread.
     */
    public GLThreadManager(int cameraId) {
        mTextureRenderer = new SurfaceTextureRenderer();
        TAG = String.format("CameraDeviceGLThread-%d", cameraId);
        mGLHandlerThread = new RequestHandlerThread(TAG, mGLHandlerCb);
    }

    /**
     * Start the thread.
     *
     * <p>
     * This must be called before queueing new frames.
     * </p>
     */
    public void start() {
        mGLHandlerThread.start();
    }

    /**
     * Wait until the thread has started.
     */
    public void waitUntilStarted() {
        mGLHandlerThread.waitUntilStarted();
    }

    /**
     * Quit the thread.
     *
     * <p>
     * No further methods can be called after this.
     * </p>
     */
    public void quit() {
        Handler handler = mGLHandlerThread.getHandler();
        handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_CLEANUP));
        mGLHandlerThread.quitSafely();
    }

    /**
     * Queue a new call to draw into a given set of surfaces.
     *
     * <p>
     * The set of surfaces passed here must be a subset of the set of surfaces passed in
     * the last call to {@link #setConfigurationAndWait}.
     * </p>
     *
     * @param targets a collection of {@link android.view.Surface}s to draw into.
     */
    public void queueNewFrame(Collection<Surface> targets) {
        Handler handler = mGLHandlerThread.getHandler();

        /**
         * Avoid queuing more than one new frame.  If we are not consuming faster than frames
         * are produced, drop frames rather than allowing the queue to back up.
         */
        if (!handler.hasMessages(MSG_NEW_FRAME)) {
            handler.sendMessage(handler.obtainMessage(MSG_NEW_FRAME, targets));
        } else {
            Log.e(TAG, "GLThread dropping frame.  Not consuming frames quickly enough!");
        }
    }

    /**
     * Configure the GL renderer for the given set of output surfaces, and block until
     * this configuration has been applied.
     *
     * @param surfaces a collection of {@link android.view.Surface}s to configure.
     */
    public void setConfigurationAndWait(Collection<Surface> surfaces) {
        Handler handler = mGLHandlerThread.getHandler();

        final ConditionVariable condition = new ConditionVariable(/*closed*/false);
        ConfigureHolder configure = new ConfigureHolder(condition, surfaces);

        Message m = handler.obtainMessage(MSG_NEW_CONFIGURATION, /*arg1*/0, /*arg2*/0, configure);
        handler.sendMessage(m);

        // Block until configuration applied.
        condition.block();
    }

    /**
     * Get the underlying surface to produce frames from.
     *
     * <p>
     * This returns the surface that is drawn into the set of surfaces passed in for each frame.
     * This method should only be called after a call to
     * {@link #setConfigurationAndWait(java.util.Collection)}.  Calling this before the first call
     * to {@link #setConfigurationAndWait(java.util.Collection)}, after {@link #quit()}, or
     * concurrently to one of these calls may result in an invalid
     * {@link android.graphics.SurfaceTexture} being returned.
     * </p>
     *
     * @return an {@link android.graphics.SurfaceTexture} to draw to.
     */
    public SurfaceTexture getCurrentSurfaceTexture() {
        return mTextureRenderer.getSurfaceTexture();
    }

    /**
     * Ignore any subsequent calls to {@link #queueNewFrame(java.util.Collection)}.
     */
    public void ignoreNewFrames() {
        mGLHandlerThread.getHandler().sendEmptyMessage(MSG_DROP_FRAMES);
    }

    /**
     * Wait until no messages are queued.
     */
    public void waitUntilIdle() {
        mGLHandlerThread.waitUntilIdle();
    }

    /**
     * Re-enable drawing new frames after a call to {@link #ignoreNewFrames()}.
     */
    public void allowNewFrames() {
        mGLHandlerThread.getHandler().sendEmptyMessage(MSG_ALLOW_FRAMES);
    }
}

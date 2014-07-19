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

package android.media.tv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

/**
 * TvInputPassthroughWrapperService represents a TV input which controls an external device
 * connected to a pass-through TV input (e.g. HDMI 1).
 * <p>
 * This service wraps around a pass-through TV input and delegates the {@link Surface} to the
 * connected TV input so that the application can show the pass-through TV input while
 * TvInputPassthroughWrapperService controls the underlying external device via a separate
 * connection. In the setup activity, the TV input should get the pass-through TV input ID, around
 * which this service will wrap. The service implementation should pass the ID via
 * {@link TvInputPassthroughWrapperService#getPassthroughInputId(String)}. In addition,
 * it needs to implement {@link TvInputPassthroughWrapperService#onCreatePassthroughWrapperSession}
 * to handle requests from the application.
 * </p>
 */
public abstract class TvInputPassthroughWrapperService extends TvInputService {
    private static final String TAG = "TvInputPassthroughWrapperService";
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
    private TvInputManager mTvInputManager;
    private Handler mHandler;

    @Override
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate()");
        super.onCreate();
        mTvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        mHandler = new Handler();
    }

    @Override
    public final Session onCreateSession(String inputId) {
        if (DEBUG) Log.d(TAG, "onCreateSession()");
        // Checks the pass-through TV input is properly setup.
        String passthroughInputId = getPassthroughInputId(inputId);
        if (passthroughInputId == null) {
            Log.w(TAG, "The passthrough TV input for input id(" + inputId + ") is not setup yet.");
            return null;
        }
        // Checks if input id from the derived class is really pass-through type.
        TvInputInfo info = mTvInputManager.getTvInputInfo(passthroughInputId);
        if (info == null || !info.isPassthroughInputType()) {
            Log.w(TAG, "Invalid TV input id from derived class: " + passthroughInputId);
            return null;
        }
        // Creates a PassthroughWrapperSession.
        PassthroughWrapperSession session = onCreatePassthroughWrapperSession();
        if (session == null) {
            return null;
        }
        // Connects to the pass-through input the external device is connected to.
        if (!session.connect(passthroughInputId)) {
            throw new IllegalStateException("WrapperSession cannot be reused.");
        }
        return session;
    }

    /**
     * Returns an implementation of {@link PassthroughWrapperSession}.
     * <p>
     * May return {@code null} if {@link TvInputPassthroughWrapperService} fails to create a
     * session.
     * </p>
     */
    public abstract PassthroughWrapperSession onCreatePassthroughWrapperSession();

    /**
     * Returns the TV input id the external device is connected to.
     * <p>
     * {@link TvInputPassthroughWrapperService} is expected to identify the pass-though TV
     * input the external device is connected to in the setup phase of this TV input.
     * May return {@code null} if the pass-though TV input is not identified yet.
     * </p>
     * @param inputId The ID of the TV input which controls the external device.
     */
    public abstract String getPassthroughInputId(String inputId);

    /**
     * Base session class for derived classes to handle the request from the application. This
     * creates additional session to the pass-through TV input internally and delegates the
     * {@link Surface} given from the application.
     */
    public abstract class PassthroughWrapperSession extends Session {
        private static final float VOLUME_ON = 1.0f;
        private static final float VOLUME_OFF = 0f;
        private TvInputManager.Session mSession;
        private Surface mSurface;
        private Float mVolume;
        private boolean mReleased;
        private int mSurfaceFormat;
        private int mSurfaceWidth;
        private int mSurfaceHeight;
        private boolean mSurfaceChanged;
        private boolean mConnectionRequested;

        private final TvInputManager.SessionCallback mSessionCallback =
                new TvInputManager.SessionCallback() {
            @Override
            public void onSessionCreated(TvInputManager.Session session) {
                if (session == null) {
                    Log.w(TAG, "Failed to create session.");
                    onPassthroughSessionCreationFailed();
                    return;
                }
                if (mReleased) {
                    session.release();
                    return;
                }
                if (mSurface != null) {
                    session.setSurface(mSurface);
                    mSurface = null;
                }
                if (mVolume != null) {
                    session.setStreamVolume(mVolume);
                    mVolume = null;
                }
                if (mSurfaceChanged) {
                    session.dispatchSurfaceChanged(mSurfaceFormat, mSurfaceWidth, mSurfaceHeight);
                    mSurfaceChanged = false;
                }
                mSession = session;
            }

            @Override
            public void onSessionReleased(TvInputManager.Session session) {
                mReleased = true;
                mSession = null;
                onPassthroughSessionReleased();
            }

            @Override
            public void onVideoAvailable(TvInputManager.Session session) {
                if (mSession == session) {
                    onPassthroughVideoAvailable();
                }
            }

            @Override
            public void onVideoUnavailable(TvInputManager.Session session, int reason) {
                if (mSession == session) {
                    onPassthroughVideoUnavailable(reason);
                }
            }

            @Override
            public void onSessionEvent(TvInputManager.Session session, String eventType,
                    Bundle eventArgs) {
                if (mSession == session) {
                    dispatchSessionEvent(eventType, eventArgs);
                }
            }
        };

        /**
         * Called when failed to create a session for pass-through TV input.
         */
        public abstract void onPassthroughSessionCreationFailed();

        /**
         * Called when the pass-through TV input session is released. This typically happens when
         * the process hosting the pass-through TV input has crashed or been killed.
         */
        public abstract void onPassthroughSessionReleased();

        /**
         * Called when the underlying pass-through TV input session calls
         * {@link #dispatchVideoAvailable()}.
         */
        public abstract void onPassthroughVideoAvailable();

        /**
         * Called when the underlying pass-through TV input session calls
         * {@link #dispatchVideoUnavailable(int)}.
         *
         * @param reason The reason why the pass-through TV input stopped the playback.
         */
        public abstract void onPassthroughVideoUnavailable(int reason);

        @Override
        public final boolean onSetSurface(Surface surface) {
            if (DEBUG) Log.d(TAG, "onSetSurface(" + surface + ")");
            if (mSession == null) {
                mSurface = surface;
            } else {
                mSession.setSurface(surface);
            }
            return true;
        }

        private boolean connect(String inputId) {
            if (mConnectionRequested) {
                return false;
            }
            mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
            mConnectionRequested = true;
            return true;
        }

        @Override
        void release() {
            super.release();
            mReleased = true;
            if (mSession != null) {
                mSession.release();
            }
        }

        @Override
        void dispatchSurfaceChanged(int format, int width, int height) {
            super.dispatchSurfaceChanged(format, width, height);
            if (mSession == null) {
                mSurfaceFormat = format;
                mSurfaceWidth = width;
                mSurfaceHeight = height;
                mSurfaceChanged = true;
            } else {
                mSession.dispatchSurfaceChanged(format, width, height);
            }
        }

        @Override
        void setStreamVolume(float volume) {
            super.setStreamVolume(volume);
            // Here, we let the pass-through TV input know only whether volume is on or off and
            // make the fine control done in the derived class to prevent that the volume is
            // controlled in the both side.
            float volumeForPassthriughInput = (volume > 0.0f) ? VOLUME_ON : VOLUME_OFF;
            if (mSession == null) {
                mVolume = Float.valueOf(volumeForPassthriughInput);
            } else {
                mSession.setStreamVolume(volumeForPassthriughInput);
            }
        }
    }
}
